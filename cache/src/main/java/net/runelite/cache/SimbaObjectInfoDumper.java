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
import com.google.gson.JsonObject;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;
import net.runelite.cache.definitions.ModelDefinition;
import net.runelite.cache.definitions.ObjectDefinition;
import net.runelite.cache.definitions.loaders.ModelLoader;
import net.runelite.cache.fs.Archive;
import net.runelite.cache.fs.Index;
import net.runelite.cache.fs.Store;
import net.runelite.cache.models.ObjExporter;
import net.runelite.cache.region.Location;
import net.runelite.cache.region.Position;
import net.runelite.cache.region.Region;
import net.runelite.cache.region.RegionLoader;
import net.runelite.cache.util.KeyProvider;
import net.runelite.cache.util.XteaKeyManager;
import org.apache.commons.cli.*;

import java.io.*;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Slf4j
@Accessors(chain = true)
public class SimbaObjectInfoDumper
{
	private static final int MAP_SCALE = 4; // this squared is the number of pixels per map square
	private final Store store;
	private static Index index;
	private static TextureManager textureManager;

	private final RegionLoader regionLoader;
	private final ObjectManager objectManager;

	private final ModelLoader modelLoader;
	public static boolean exportFullMap = false;

	private static boolean exportChunks = true;

	private static final boolean exportEmptyJSONs = false;


	public SimbaObjectInfoDumper(Store store, KeyProvider keyProvider)
	{
		this(store, new RegionLoader(store, keyProvider));
	}

	public SimbaObjectInfoDumper(Store store, RegionLoader regionLoader)
	{
		this.store = store;
		this.regionLoader = regionLoader;
		this.objectManager = new ObjectManager(store);
		this.modelLoader = new ModelLoader();
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
		final String outputDirectory = cmd.getOptionValue("outputdir") + File.separator + cacheName;
		final String outputDirectoryEx = outputDirectory + File.separator + "objects";

		XteaKeyManager xteaKeyManager = new XteaKeyManager();
		try (FileInputStream fin = new FileInputStream(xteaJSONPath))
		{
			xteaKeyManager.loadKeys(fin);
		}

		File base = new File(cacheDirectory);
		File outDir;

		if (exportFullMap) outDir = new File(outputDirectoryEx);
		else outDir = new File(outputDirectory);

		if (outDir.mkdirs()) throw new RuntimeException("Failed to create output path: " + outDir.getPath());
		if (!exportFullMap) exportChunks = true;

		try (Store store = new Store(base))
		{
			store.load();

			SimbaObjectInfoDumper dumper = new SimbaObjectInfoDumper(store, xteaKeyManager);
			dumper.load();

			ZipOutputStream zip = null;
			if (exportChunks) {
				zip = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(new File(outputDirectory, "objects.zip"))));
			}

			for (int i = 0; i < Region.Z; ++i)
			{
				JsonArray objects = dumper.mapRegions(i, zip);
				if (exportFullMap) {
					File jsonFile = new File(outDir, "objects-" + i + ".json");
					if (jsonFile.createNewFile()) {
						FileWriter fileWriter = new FileWriter(jsonFile);
						fileWriter.write(objects.toString());
						fileWriter.flush();
						fileWriter.close();
						log.info("Wrote json {}", jsonFile);
					}
				}
			}

			if (zip != null) zip.close();
		}
	}

	public SimbaObjectInfoDumper load() throws IOException
	{
		objectManager.load();
		index = store.getIndex(IndexType.MODELS);
		textureManager = new TextureManager(store);
		textureManager.load();

		loadRegions();
		return this;
	}

	public JsonArray mapRegions(int z, ZipOutputStream zip)
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

		mapRegions(json, z, zip);
		return cleanJSON(json);
	}

	private JsonArray cleanJSON(JsonArray json) {
		JsonArray result = new JsonArray();

		outter:
		for (int i = 0; i < json.size(); i++) {
			JsonObject obj = json.get(i).getAsJsonObject();
			int id = obj.get("id").getAsInt();

			for (int j = 0; j < result.size(); j++) {
				JsonObject res = result.get(j).getAsJsonObject();

				if (id == res.get("id").getAsInt()){
					res.get("coordinates").getAsJsonArray().add(obj.get("coordinates").getAsJsonArray().get(0));
					res.get("rotations").getAsJsonArray().add(obj.get("rotations").getAsJsonArray().get(0));
					if (id == 10060 && res.get("coordinates").getAsJsonArray().size() == 3) {
						JsonArray coords = new JsonArray();
						coords.add(8554);
						coords.add(36468);
						res.get("coordinates").getAsJsonArray().add(coords);
						res.get("rotations").getAsJsonArray().add(0);
					}
					continue outter;
				}
			}
			result.add(obj);
		}
		return result;
	}

	private void mapObjects(JsonArray json, int drawBaseX, int drawBaseY, Region region, int z) throws IOException {
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
						ObjectDefinition object = findObject(location.getId());

						if (object.getName().equalsIgnoreCase("null")) continue;
						if (object.getInteractType() == 0) continue;

						int height = 0;
						List<Integer> colors = new ArrayList<>();

						for (int i = 0; i < object.getObjectModels().length; i++) {
							Archive archive = index.getArchive(object.getObjectModels()[i]);
							byte[] contents = archive.decompress(store.getStorage().loadArchive(archive));
							ModelDefinition model = modelLoader.load(archive.getArchiveId(), contents);

							ObjExporter exporter = new ObjExporter(textureManager, model);
							if (height == 0) height = exporter.getSimbaHeight();
							colors.addAll(exporter.getSimbaColors());
						}

						int x = (drawBaseX + localX) * MAP_SCALE;
						int y = (drawBaseY + (Region.Y - object.getSizeY() - localY)) * MAP_SCALE;

						int centerX = x + object.getSizeX() * MAP_SCALE/2;
						int centerY = y + object.getSizeY() * MAP_SCALE/2;

						JsonObject obj = new JsonObject();

						obj.addProperty("id",  object.getId());
						obj.addProperty("name", object.getName());
						obj.addProperty("type", type);

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
						size.add(height);
						obj.add("size", size);

						JsonArray rotations = new JsonArray();
						if (object.getSizeX() == object.getSizeY()) rotations.add(0);
						else rotations.add(location.getOrientation());
						obj.add("rotations", rotations);

						JsonArray jsonColors = new JsonArray();
						for (int i = 0; i < colors.size(); i++) {
							jsonColors.add(colors.get(i));
						}
						obj.add("colors", jsonColors);
						json.add(obj);
					}
				}
			}
		}
	}

	private void mapRegions(JsonArray json, int z, ZipOutputStream zip)
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
			try {
				mapObjects(regionJSON, drawBaseX, drawBaseY, region, z);
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
			regionJSON = cleanJSON(regionJSON);

			if (regionJSON.size() > 0) json.addAll(regionJSON);
			if (exportChunks && (exportEmptyJSONs || regionJSON.size() > 0)) {
				try {
					zip.putNextEntry(new ZipEntry(z + File.separator + region.getRegionX() + "-" + region.getRegionY() + ".json"));
					zip.write(regionJSON.toString().getBytes());
				} catch (IOException e) {
					throw new RuntimeException(e);
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