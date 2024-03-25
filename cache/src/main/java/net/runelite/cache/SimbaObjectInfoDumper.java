/*
 * Copyright (c) 2016-2017, Adam <Adam@sigterm.info>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package net.runelite.cache;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;
import net.runelite.cache.definitions.ObjectDefinition;
import net.runelite.cache.definitions.OverlayDefinition;
import net.runelite.cache.definitions.SpriteDefinition;
import net.runelite.cache.definitions.UnderlayDefinition;
import net.runelite.cache.definitions.loaders.OverlayLoader;
import net.runelite.cache.definitions.loaders.SpriteLoader;
import net.runelite.cache.definitions.loaders.UnderlayLoader;
import net.runelite.cache.fs.*;
import net.runelite.cache.item.RSTextureProvider;
import net.runelite.cache.models.JagexColor;
import net.runelite.cache.region.Location;
import net.runelite.cache.region.Position;
import net.runelite.cache.region.Region;
import net.runelite.cache.region.RegionLoader;
import net.runelite.cache.util.BigBufferedImage;
import net.runelite.cache.util.KeyProvider;
import net.runelite.cache.util.XteaKeyManager;
import org.apache.commons.cli.*;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;

@Slf4j
@Accessors(chain = true)
public class SimbaObjectInfoDumper
{
	private static final int MAP_SCALE = 4; // this squared is the number of pixels per map square
	private final Store store;

	private final Map<Integer, UnderlayDefinition> underlays = new HashMap<>();
	private final Map<Integer, OverlayDefinition> overlays = new HashMap<>();

	private final RegionLoader regionLoader;
	private final ObjectManager objectManager;

	private final boolean exportChunks = true;


	public SimbaObjectInfoDumper(Store store, KeyProvider keyProvider)
	{
		this(store, new RegionLoader(store, keyProvider));
	}

	public SimbaObjectInfoDumper(Store store, RegionLoader regionLoader)
	{
		this.store = store;
		this.regionLoader = regionLoader;
		this.objectManager = new ObjectManager(store);
	}

	protected double random()
	{
		// the client would use a random value here, but we prefer determinism
		return 0.5;
	}

	public static void main(String[] args) throws IOException
	{
		Options options = new Options();
		options.addOption(Option.builder().longOpt("cachedir").hasArg().required().build());
		options.addOption(Option.builder().longOpt("cachename").hasArg().required().build());
		options.addOption(Option.builder().longOpt("outputdir").hasArg().required().build());

		CommandLineParser parser = new DefaultParser();
		CommandLine cmd;
		try
		{
			cmd = parser.parse(options, args);
		}
		catch (ParseException ex)
		{
			System.err.println("Error parsing command line options: " + ex.getMessage());
			System.exit(-1);
			return;
		}

		final String mainDir = cmd.getOptionValue("cachedir");
		final String cacheName = cmd.getOptionValue("cachename");

		final String cacheDirectory = mainDir + File.separator + cacheName + File.separator + "cache";

		final String xteaJSONPath = mainDir + File.separator + cacheName + File.separator + cacheName.replace("cache-", "keys-") + ".json";
		final String outputDirectory = cmd.getOptionValue("outputdir") + File.separator + cacheName + File.separator + "objects";

		XteaKeyManager xteaKeyManager = new XteaKeyManager();
		try (FileInputStream fin = new FileInputStream(xteaJSONPath))
		{
			xteaKeyManager.loadKeys(fin);
		}

		File base = new File(cacheDirectory);
		File outDir = new File(outputDirectory);
		outDir.mkdirs();

		try (Store store = new Store(base))
		{
			store.load();

			SimbaObjectInfoDumper dumper = new SimbaObjectInfoDumper(store, xteaKeyManager);
			dumper.load();

			for (int i = 0; i < Region.Z; ++i)
			{
				File mapDir = new File(outputDirectory + File.separator + i);
				if (dumper.exportChunks) mapDir.mkdirs();
				JsonArray objects = dumper.drawRegions(i, mapDir);

				File jsonFile = new File(outDir, "objects-" + i + ".json");
				jsonFile.createNewFile();

				FileWriter fileWriter = new FileWriter(jsonFile);
				fileWriter.write(objects.toString());
				fileWriter.flush();
				fileWriter.close();
				log.info("Wrote json {}", jsonFile);
			}
		}
	}

	public SimbaObjectInfoDumper load() throws IOException
	{
		objectManager.load();

		TextureManager textureManager = new TextureManager(store);
		textureManager.load();

		loadRegions();
		return this;
	}

	public JsonArray drawRegions(int z, File outDir)
	{
		int minX = regionLoader.getLowestX().getBaseX();
		int minY = regionLoader.getLowestY().getBaseY();

		int maxX = regionLoader.getHighestX().getBaseX() + Region.X;
		int maxY = regionLoader.getHighestY().getBaseY() + Region.Y;

		int dimX = maxX - minX;
		int dimY = maxY - minY;

		int pixelsX = dimX * MAP_SCALE;
		int pixelsY = dimY * MAP_SCALE;

		log.info("Map image dimensions: {}px x {}px, {}px per map square ({} MB). Max memory: {}mb", pixelsX, pixelsY,
			MAP_SCALE, (pixelsX * pixelsY * 3 / 1024 / 1024),
			Runtime.getRuntime().maxMemory() / 1024L / 1024L);

		JsonArray json = new JsonArray();

		drawRegions(json, z, outDir);

		return cleanJSON(json);
	}

	private JsonArray cleanJSON(JsonArray json) {
		JsonArray result = new JsonArray();

		outter:
		for (int i = 0; i < json.size(); i++) {
			JsonObject obj = json.get(i).getAsJsonObject();

			for (int j = 0; j < result.size(); j++) {
				JsonObject res = result.get(j).getAsJsonObject();
				if (obj.get("id").getAsInt() == res.get("id").getAsInt()){
					res.get("coordinates").getAsJsonArray().add(obj.get("coordinates").getAsJsonArray().get(0));
					res.get("rotations").getAsJsonArray().add(obj.get("rotations").getAsJsonArray().get(0));
					continue outter;
				}
			}
			result.add(obj);
		}
		return result;
	}

	private void drawRegions(JsonArray json, int z, File outDir)
	{
		for (Region region : regionLoader.getRegions())
		{
			int baseX = region.getBaseX();
			int baseY = region.getBaseY();

			// to pixel X
			int drawBaseX = baseX - regionLoader.getLowestX().getBaseX();

			// to pixel Y. top most y is 0, but the top most
			// region has the greatest y, so invert
			int drawBaseY = regionLoader.getHighestY().getBaseY() - baseY;

			JsonArray regionJSON = new JsonArray();
			drawObjects(regionJSON, drawBaseX, drawBaseY, region, z);
			regionJSON = cleanJSON(regionJSON);

			json.addAll(regionJSON);

			if (exportChunks) {
				try {
					File jsonFile = new File(outDir, region.getRegionY() + "-" + region.getRegionX() + ".json");
					jsonFile.createNewFile();
					FileWriter fileWriter = new FileWriter(jsonFile);
					fileWriter.write(regionJSON.toString());
					fileWriter.flush();
					fileWriter.close();
				} catch (IOException e) {
					throw new RuntimeException(e);
				}
			}
		}
	}

	private void drawObjects(JsonArray json, int drawBaseX, int drawBaseY, Region region, int z)
	{
		List<Location> planeLocs = new ArrayList<>();
		List<Location> pushDownLocs = new ArrayList<>();
		List<List<Location>> layers = Arrays.asList(planeLocs, pushDownLocs);
		for (int localX = 0; localX < Region.X; localX++)
		{
			int regionX = localX + region.getBaseX();
			for (int localY = 0; localY < Region.Y; localY++)
			{
				int regionY = localY + region.getBaseY();

				planeLocs.clear();
				pushDownLocs.clear();
				boolean isBridge = (region.getTileSetting(1, localX, localY) & 2) != 0;

				int tileZ = z + (isBridge ? 1 : 0);

				for (Location loc : region.getLocations())
				{
					Position pos = loc.getPosition();
					if (pos.getX() != regionX || pos.getY() != regionY) continue;

					if (pos.getZ() == tileZ && (region.getTileSetting(z, localX, localY) & 24) == 0)
						planeLocs.add(loc);
					else if (z < 3 && pos.getZ() == tileZ + 1 && (region.getTileSetting(z + 1, localX, localY) & 8) != 0)
						pushDownLocs.add(loc);
				}

				for (List<Location> locs : layers)
				{
					for (Location location : locs)
					{
						int type = location.getType();
						//22=ground textures (carpets, rubble, they are not visible on the mm afaik).
						//9=something related to diagonal walls?
						//10=trees, rocks, plants and objects you can interact with
						//11=another type of tree and rocks. You can't interact I think

						if ((type >= 0 && type <= 3)  || (type > 9 && type <= 11))
						{
							ObjectDefinition object = findObject(location.getId());
							if (object.getName() == "null") continue;
							if (object.getInteractType() == 0) continue;


							int x = (drawBaseX + localX) * MAP_SCALE;
							int y = (drawBaseY + (Region.Y - object.getSizeY() - localY)) * MAP_SCALE;

							int centerX = x + object.getSizeY() * MAP_SCALE/2;
							int centerY;
							if (object.getSizeY() > 2) centerY = y - MAP_SCALE;
							else centerY = y;

							JsonObject obj = new JsonObject();

							obj.addProperty("id",  object.getId());
							obj.addProperty("name", object.getName());
							obj.addProperty("category", object.getCategory());

							JsonArray jsonActions = new JsonArray();
							String[] actions = object.getActions();
							for (int i = 0; i < actions.length; i++) {
								if (actions[i] != null) jsonActions.add(actions[i]);
							}
							obj.add("actions", jsonActions);

							JsonArray coords = new JsonArray();
							JsonArray p = new JsonArray();
							p.add(centerX);
							p.add(centerY);
							coords.add(p);
							obj.add("coordinates", coords);

							JsonArray size = new JsonArray();
							size.add(object.getSizeX());
							size.add(object.getSizeY());
							obj.add("size", size);

							JsonArray rotations = new JsonArray();
							if (object.getSizeX() == object.getSizeY()) rotations.add(0);
							else rotations.add(location.getOrientation());
							obj.add("rotations", rotations);

							json.add(obj);
						}
					}
				}
			}
		}
	}

	private ObjectDefinition findObject(int id)
	{
		return objectManager.getObject(id);
	}
	private void loadRegions() throws IOException
	{
		regionLoader.loadRegions();
		regionLoader.calculateBounds();

		log.debug("North most region: {}", regionLoader.getLowestY().getBaseY());
		log.debug("South most region: {}", regionLoader.getHighestY().getBaseY());
		log.debug("West most region:  {}", regionLoader.getLowestX().getBaseX());
		log.debug("East most region:  {}", regionLoader.getHighestX().getBaseX());
	}
}
