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

import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;
import net.runelite.cache.definitions.*;
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
import java.io.IOException;
import java.nio.Buffer;
import java.util.List;
import java.util.*;

@Slf4j
@Accessors(chain = true)
public class SimbaCollisionMapDumper
{
	private static final int MAP_SCALE = 4; // this squared is the number of pixels per map square
	private static final int BLEND = 5; // number of surrounding tiles for ground blending
	private static int[] colorPalette = JagexColor.createPalette(JagexColor.BRIGHTNESS_MAX);

	private static int[][] TILE_SHAPE_2D = new int[][]{{0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0}, {1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1}, {1, 0, 0, 0, 1, 1, 0, 0, 1, 1, 1, 0, 1, 1, 1, 1}, {1, 1, 0, 0, 1, 1, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0}, {0, 0, 1, 1, 0, 0, 1, 1, 0, 0, 0, 1, 0, 0, 0, 1}, {0, 1, 1, 1, 0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1}, {1, 1, 1, 0, 1, 1, 1, 0, 1, 1, 1, 1, 1, 1, 1, 1}, {1, 1, 0, 0, 1, 1, 0, 0, 1, 1, 0, 0, 1, 1, 0, 0}, {0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 1, 1, 0, 0}, {1, 1, 1, 1, 1, 1, 1, 1, 0, 1, 1, 1, 0, 0, 1, 1}, {1, 1, 1, 1, 1, 1, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0}, {0, 0, 0, 0, 0, 0, 1, 1, 0, 1, 1, 1, 0, 1, 1, 1}, {0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 0, 1, 1, 1, 1}};
	private static int[][] TILE_ROTATION_2D = new int[][]{{0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15}, {12, 8, 4, 0, 13, 9, 5, 1, 14, 10, 6, 2, 15, 11, 7, 3}, {15, 14, 13, 12, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1, 0}, {3, 7, 11, 15, 2, 6, 10, 14, 1, 5, 9, 13, 0, 4, 8, 12}};

	private static final int collisionColor = 0x000000;
	private static final int walkableColor = 0xFFFFFF;

	private final Store store;

	private final Map<Integer, UnderlayDefinition> underlays = new HashMap<>();
	private final Map<Integer, OverlayDefinition> overlays = new HashMap<>();

	private final RegionLoader regionLoader;
	private final AreaManager areas;
	private final SpriteManager sprites;
	private RSTextureProvider rsTextureProvider;
	private final ObjectManager objectManager;

	private final boolean exportChunks = true;

	private static final boolean debugMap = false;

	@Getter
	@Setter
	private boolean renderMap = true;

	@Getter
	@Setter
	private boolean renderObjects = true;

	@Getter
	@Setter
	private boolean transparency = false;

	@Getter
	@Setter
	private boolean lowMemory = false;

	public SimbaCollisionMapDumper(Store store, KeyProvider keyProvider)
	{
		this(store, new RegionLoader(store, keyProvider));
	}

	public SimbaCollisionMapDumper(Store store, RegionLoader regionLoader)
	{
		this.store = store;
		this.regionLoader = regionLoader;
		this.areas = new AreaManager(store);
		this.sprites = new SpriteManager(store);
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
		final String outputDirectory = cmd.getOptionValue("outputdir") + File.separator + cacheName + File.separator + "collision";

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

			SimbaCollisionMapDumper dumper = new SimbaCollisionMapDumper(store, xteaKeyManager);
			dumper.load();

			for (int i = 0; i < Region.Z; ++i)
			{
				File mapDir = new File(outputDirectory + File.separator + i);
				if (dumper.exportChunks) mapDir.mkdirs();
				BufferedImage image = dumper.drawRegions(i, mapDir);

				File imageFile = new File(outDir, "img-" + i + ".png");

				ImageIO.write(image, "png", imageFile);
				log.info("Wrote image {}", imageFile);
			}
		}
	}

	public SimbaCollisionMapDumper setBrightness(double brightness)
	{
		colorPalette = JagexColor.createPalette(brightness);
		return this;
	}

	public SimbaCollisionMapDumper load() throws IOException
	{
		loadUnderlays(store);
		loadOverlays(store);
		objectManager.load();

		TextureManager textureManager = new TextureManager(store);
		textureManager.load();
		rsTextureProvider = new RSTextureProvider(textureManager, sprites);

		loadRegions();
		areas.load();
		sprites.load();
		loadSprites();
		return this;
	}

	public BufferedImage drawRegions(int z, File outDir)
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

		BufferedImage image;
		if (lowMemory)
			image = BigBufferedImage.create(pixelsX, pixelsY, transparency ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
		else
			image = new BufferedImage(pixelsX, pixelsY, transparency ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);

		drawRegions(image, z, outDir);

		return image;
	}

	private void drawRegions(BufferedImage image, int z, File outDir)
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

			drawRegions(image, drawBaseX, drawBaseY, z, region);
			drawObjects(image, drawBaseX, drawBaseY, region, z);

			if (exportChunks) {
				try {
					BufferedImage chunk = image.getSubimage(drawBaseX * MAP_SCALE, drawBaseY * MAP_SCALE, Region.X * MAP_SCALE, Region.Y * MAP_SCALE);
					File imageFile = new File(outDir, region.getRegionY() + "-" + region.getRegionX() + ".png");
					ImageIO.write(chunk, "png", imageFile);
				} catch (IOException e) {
					throw new RuntimeException(e);
				}
			}
		}
	}

	private void drawRegions(BufferedImage image, int drawBaseX, int drawBaseY, int z, Region region)
	{
		if (!renderMap) return;

		int[][][] map = new int[4][][];

		for (int x = 0; x < Region.X; ++x)
		{
			for (int y = 0; y < Region.Y; ++y)
			{
				boolean isBridge = (region.getTileSetting(1, x, Region.Y - y - 1) & 2) != 0;
				int tileZ = z + (isBridge ? 1 : 0);
				if (tileZ >= Region.Z) continue;

				int tileSetting = region.getTileSetting(z, x, Region.Y - y - 1);
				if ((tileSetting & 24) == 0)
				{
					if (z == 0 && isBridge) drawTile(image, map, region, drawBaseX, drawBaseY, 0, x, y);
					drawTile(image, map, region, drawBaseX, drawBaseY, tileZ, x, y);
				}

				if (tileZ < 3)
				{
					int upTileSetting = region.getTileSetting(z + 1, x, Region.Y - y - 1);
					if ((upTileSetting & 8) != 0) drawTile(image, map, region, drawBaseX, drawBaseY, tileZ + 1, x, y);
				}
			}
		}
	}

	private void drawTile(BufferedImage to, int[][][] planes, Region region, int drawBaseX, int drawBaseY, int z, int x, int y)
	{
		int tileSetting = region.getTileSetting(z, x, Region.Y - y - 1);
		boolean unWalkable = ((tileSetting & 1) != 0);

		int[][] pixels = planes[z];

		if ((pixels == null))
		{
			pixels = planes[z] = new int[Region.X * MAP_SCALE][Region.Y * MAP_SCALE];
			if (debugMap) //MAYBE CAN REMOVE?
				drawRegions(pixels, region, z);
		}

		assert pixels != null;

		for (int i = 0; i < MAP_SCALE; ++i)
		{
			for (int j = 0; j < MAP_SCALE; ++j)
			{
				int argb;
				if (debugMap) argb = pixels[x * MAP_SCALE + i][y * MAP_SCALE + j];
				else  argb = walkableColor;

				int tempX = drawBaseX * MAP_SCALE + x * MAP_SCALE + i;
				int tempY = drawBaseY * MAP_SCALE + y * MAP_SCALE + j;

				if (unWalkable) to.setRGB(tempX, tempY, collisionColor);
				else if (argb != 0) to.setRGB(tempX,tempY, argb);
			}
		}
	}

	private void drawRegions(int[][] pixels, Region region, int z)
	{
		int baseX = region.getBaseX();
		int baseY = region.getBaseY();

		int len = Region.X + BLEND * 2;
		int[] hues = new int[len];
		int[] sats = new int[len];
		int[] light = new int[len];
		int[] mul = new int[len];
		int[] num = new int[len];

		boolean hasLeftRegion = regionLoader.findRegionForWorldCoordinates(baseX - 1, baseY) != null;
		boolean hasRightRegion = regionLoader.findRegionForWorldCoordinates(baseX + Region.X, baseY) != null;
		boolean hasUpRegion = regionLoader.findRegionForWorldCoordinates(baseX, baseY + Region.Y) != null;
		boolean hasDownRegion = regionLoader.findRegionForWorldCoordinates(baseX, baseY - 1) != null;

		for (int xi = (hasLeftRegion ? -BLEND * 2 : -BLEND); xi < Region.X + (hasRightRegion ? BLEND * 2 : BLEND); ++xi)
		{
			for (int yi = (hasDownRegion ? -BLEND : 0); yi < Region.Y + (hasUpRegion ? BLEND : 0); ++yi)
			{
				int xr = xi + BLEND;
				if (xr >= (hasLeftRegion ? -BLEND : 0) && xr < Region.X + (hasRightRegion ? BLEND : 0))
				{
					Region r = regionLoader.findRegionForWorldCoordinates(baseX + xr, baseY + yi);
					if (r != null)
					{
						int underlayId = r.getUnderlayId(z, convert(xr), convert(yi));
						if (underlayId > 0)
						{
							UnderlayDefinition underlay = findUnderlay(underlayId - 1);
							hues[yi + BLEND] += underlay.getHue();
							sats[yi + BLEND] += underlay.getSaturation();
							light[yi + BLEND] += underlay.getLightness();
							mul[yi + BLEND] += underlay.getHueMultiplier();
							num[yi + BLEND]++;
						}
					}
				}

				int xl = xi - BLEND;
				if (xl >= (hasLeftRegion ? -BLEND : 0) && xl < Region.X + (hasRightRegion ? BLEND : 0))
				{
					Region r = regionLoader.findRegionForWorldCoordinates(baseX + xl, baseY + yi);
					if (r != null)
					{
						int underlayId = r.getUnderlayId(z, convert(xl), convert(yi));
						if (underlayId > 0)
						{
							UnderlayDefinition underlay = findUnderlay(underlayId - 1);
							hues[yi + BLEND] -= underlay.getHue();
							sats[yi + BLEND] -= underlay.getSaturation();
							light[yi + BLEND] -= underlay.getLightness();
							mul[yi + BLEND] -= underlay.getHueMultiplier();
							num[yi + BLEND]--;
						}
					}
				}
			}

			if (xi >= 0 && xi < Region.X)
			{
				int runningHues = 0;
				int runningSat = 0;
				int runningLight = 0;
				int runningMultiplier = 0;
				int runningNumber = 0;

				for (int yi = (hasDownRegion ? -BLEND * 2 : -BLEND); yi < Region.Y + (hasUpRegion ? BLEND * 2 : BLEND); ++yi)
				{
					int yu = yi + BLEND;
					if (yu >= (hasDownRegion ? -BLEND : 0) && yu < Region.Y + (hasUpRegion ? BLEND : 0))
					{
						runningHues += hues[yu + BLEND];
						runningSat += sats[yu + BLEND];
						runningLight += light[yu + BLEND];
						runningMultiplier += mul[yu + BLEND];
						runningNumber += num[yu + BLEND];
					}

					int yd = yi - BLEND;
					if (yd >= (hasDownRegion ? -BLEND : 0) && yd < Region.Y + (hasUpRegion ? BLEND : 0))
					{
						runningHues -= hues[yd + BLEND];
						runningSat -= sats[yd + BLEND];
						runningLight -= light[yd + BLEND];
						runningMultiplier -= mul[yd + BLEND];
						runningNumber -= num[yd + BLEND];
					}

					if (yi >= 0 && yi < Region.Y)
					{
						Region r = regionLoader.findRegionForWorldCoordinates(baseX + xi, baseY + yi);
						if (r != null)
						{
							int underlayId = r.getUnderlayId(z, convert(xi), convert(yi));
							int overlayId = r.getOverlayId(z, convert(xi), convert(yi));

							if (underlayId > 0 || overlayId > 0)
							{
								int underlayHsl = -1;
								if (underlayId > 0)
								{
									int avgHue = runningHues * 256 / runningMultiplier;
									int avgSat = runningSat / runningNumber;
									int avgLight = runningLight / runningNumber;
									// randomness is added to avgHue here

									if (avgLight < 0) avgLight = 0;
									else if (avgLight > 255) avgLight = 255;


									underlayHsl = packHsl(avgHue, avgSat, avgLight);
								}

								int underlayRgb = 0;
								if (underlayHsl != -1)
								{
									int var0 = method1792(underlayHsl, 96);
									underlayRgb = colorPalette[var0] | 0xFF000000;
								}

								int shape, rotation;
								int overlayRgb = 0;

								if (overlayId == 0) shape = rotation = 0;
								else
								{
									shape = r.getOverlayPath(z, convert(xi), convert(yi)) + 1;
									rotation = r.getOverlayRotation(z, convert(xi), convert(yi));

									OverlayDefinition overlayDefinition = findOverlay(overlayId - 1);
									int overlayTexture = overlayDefinition.getTexture();
									int hsl;

									if (overlayTexture >= 0)
										hsl = rsTextureProvider.getAverageTextureRGB(overlayTexture);
									else if (overlayDefinition.getRgbColor() == 0xFF_00FF)
										hsl = -2;
									else
									{
										// randomness added here
										int overlayHsl = packHsl(overlayDefinition.getHue(), overlayDefinition.getSaturation(), overlayDefinition.getLightness());
										hsl = overlayHsl;
									}

									if (hsl != -2)
									{
										int var0 = adjustHSLListness0(hsl, 96);
										overlayRgb = colorPalette[var0] | 0xFF000000;
									}

									if (overlayDefinition.getSecondaryRgbColor() != -1)
									{
										int hue = overlayDefinition.getOtherHue();
										int sat = overlayDefinition.getOtherSaturation();
										int olight = overlayDefinition.getOtherLightness();
										hsl = packHsl(hue, sat, olight);
										int var0 = adjustHSLListness0(hsl, 96);
										overlayRgb = colorPalette[var0] | 0xFF000000;
									}
								}

								if (shape == 0)
								{
									int drawX = xi;
									int drawY = Region.Y - 1 - yi;
									if (underlayRgb != 0)
									{
										drawMapSquare(pixels, drawX, drawY, underlayRgb);
									}
								}
								else if (shape == 1)
								{
									int drawX = xi;
									int drawY = Region.Y - 1 - yi;
									drawMapSquare(pixels, drawX, drawY, overlayRgb);
								}
								else
								{
									int drawX = xi * MAP_SCALE;
									int drawY = (Region.Y - 1 - yi) * MAP_SCALE;
									int[] tileShapes = TILE_SHAPE_2D[shape];
									int[] tileRotations = TILE_ROTATION_2D[rotation];
									if (underlayRgb != 0)
									{
										int rotIdx = 0;
										for (int i = 0; i < Region.Z; ++i)
										{
											int p1 = tileShapes[tileRotations[rotIdx++]] == 0 ? underlayRgb : overlayRgb;
											int p2 = tileShapes[tileRotations[rotIdx++]] == 0 ? underlayRgb : overlayRgb;
											int p3 = tileShapes[tileRotations[rotIdx++]] == 0 ? underlayRgb : overlayRgb;
											int p4 = tileShapes[tileRotations[rotIdx++]] == 0 ? underlayRgb : overlayRgb;
											pixels[drawX + 0][drawY + i] = p1;
											pixels[drawX + 1][drawY + i] = p2;
											pixels[drawX + 2][drawY + i] = p3;
											pixels[drawX + 3][drawY + i] = p4;
										}
									}
									else
									{
										int rotIdx = 0;
										for (int i = 0; i < Region.Z; ++i)
										{
											int p1 = tileShapes[tileRotations[rotIdx++]];
											int p2 = tileShapes[tileRotations[rotIdx++]];
											int p3 = tileShapes[tileRotations[rotIdx++]];
											int p4 = tileShapes[tileRotations[rotIdx++]];

											if (p1 != 0)
											{
												pixels[drawX + 0][drawY + i] = overlayRgb;
											}

											if (p2 != 0)
											{
												pixels[drawX + 1][drawY + i] = overlayRgb;
											}

											if (p3 != 0)
											{
												pixels[drawX + 2][drawY + i] = overlayRgb;
											}

											if (p4 != 0)
											{
												pixels[drawX + 3][drawY + i] = overlayRgb;
											}
										}
									}
								}
							}
						}
					}
				}
			}
		}
	}

	private static int convert(int d)
	{
		if (d >= 0) return d % 64;
		return 64 - -(d % 64) - 1;
	}

	private void paintTile(BufferedImage image, int x, int y, int color){
		for (int i = 0; i < MAP_SCALE; i++) {
			for (int j = 0; j < MAP_SCALE; j++) {
				image.setRGB(x + i, y + j, color);
			}
		}
	}

	private void drawObjects(BufferedImage image, int drawBaseX, int drawBaseY, Region region, int z)
	{
		if (!renderObjects) return;

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

						if (type >= 0 && type <= 3)
						{
							int rotation = location.getOrientation();

							ObjectDefinition object = findObject(location.getId());

							int drawX = (drawBaseX + localX) * MAP_SCALE;
							int drawY = (drawBaseY + (Region.Y - object.getSizeY() - localY)) * MAP_SCALE;

							int rgb = collisionColor;
							int doorColor = 0xFF0000;
							if (object.getWallOrDoor() != 0) rgb = doorColor;

							rgb |= 0xFF000000;

							if (object.getMapSceneID() != -1) continue;

							if (drawX >= 0 && drawY >= 0 && drawX < image.getWidth() && drawY < image.getHeight())
							{
								if (type == 0 || type == 2)
								{
									for (int i = 0; i < MAP_SCALE; i++) {
										if (rotation == 0)      image.setRGB(drawX, drawY + i, rgb);
										else if (rotation == 1) image.setRGB(drawX + i, drawY, rgb);
										else if (rotation == 2) image.setRGB(drawX + MAP_SCALE-1, drawY + i, rgb);
										else if (rotation == 3) image.setRGB(drawX + i, drawY + MAP_SCALE-1, rgb);
									}
								}

								if (type == 3)
								{
									if (rotation == 0)      image.setRGB(drawX, drawY, rgb);
									else if (rotation == 1) image.setRGB(drawX + MAP_SCALE - 1, drawY, rgb);
									else if (rotation == 2) image.setRGB(drawX + MAP_SCALE - 1, drawY + MAP_SCALE - 1, rgb);
									else if (rotation == 3) image.setRGB(drawX, drawY + MAP_SCALE - 1, rgb);
								}

								if (type == 2)
								{
									for (int i = 0; i < MAP_SCALE; i++) {
										if (rotation == 0)      image.setRGB(drawX + i, drawY, rgb);
										else if (rotation == 1) image.setRGB(drawX + MAP_SCALE-1, drawY + i, rgb);
										else if (rotation == 2) image.setRGB(drawX + i, drawY + MAP_SCALE-1, rgb);
										else if (rotation == 3) image.setRGB(drawX, drawY + i, rgb);
									}
								}
							}
						}

						if (type == 9)
						{
							ObjectDefinition object = findObject(location.getId());

							int drawX = (drawBaseX + localX) * MAP_SCALE;
							int drawY = (drawBaseY + (Region.Y - object.getSizeY() - localY)) * MAP_SCALE;

							if (drawX >= 0 && drawY >= 0 && drawX < image.getWidth() && drawY < image.getHeight())
							{
								for (int x = 0; x < MAP_SCALE; x++) {
									for (int y = 0; y < MAP_SCALE; y++) {
										image.setRGB(drawX + x, drawY + y, collisionColor);
									}
								}
							}
						}

						if (type > 9 && type <= 11)
						{
							ObjectDefinition object = findObject(location.getId());

							int drawX = (drawBaseX + localX) * MAP_SCALE;
							int drawY = (drawBaseY + (Region.Y - object.getSizeY() - localY)) * MAP_SCALE;

							if (object.getInteractType() == 0) continue;

							if ((type == 10) || (type == 11)) {
								int rotation = location.getOrientation();

								if (object.getSizeX() == object.getSizeY()) {
									for (int sX = 0; sX < object.getSizeX(); sX++) {
										for (int sY = 0; sY < object.getSizeY(); sY++) {
											for (int n = 0; n < 4; n++) {
												for (int l = 0; l < 4; l++) {
													image.setRGB(drawX + n + sX * MAP_SCALE, drawY + l + sY * MAP_SCALE, collisionColor);
												}
											}
										}
									}
									continue;
								}

								if (rotation == 0 || rotation == 2) {
									for (int sX = 0; sX < object.getSizeX(); sX++) {
										for (int sY = 0; sY < object.getSizeY(); sY++) {
											paintTile(image, drawX + sX * MAP_SCALE, drawY + sY * MAP_SCALE, collisionColor);
										}
									}
								}

								else {
									//Don't ask questions about this, just accept it as a fact.
									if (object.getSizeX() < object.getSizeY()) {
										for (int sX = 0; sX < object.getSizeX(); sX++) {
											for (int sY = 0; sY < object.getSizeY(); sY++) {
												paintTile(image, drawX + sX * MAP_SCALE, drawY + sY * MAP_SCALE + MAP_SCALE, collisionColor);
											}
										}
									}
									else {
										int centerY;

										if (object.getSizeY() > 2) centerY = drawY - MAP_SCALE;
										else centerY = drawY;

										for (int sX = 0; sX < object.getSizeY(); sX++) {
											for (int sY = 0; sY < object.getSizeX() / 2; sY++) {
												paintTile(image, drawX + sX * MAP_SCALE, centerY + sY * MAP_SCALE, collisionColor);
												paintTile(image, drawX + sX * MAP_SCALE, centerY - sY * MAP_SCALE - MAP_SCALE, collisionColor);
											}
										}
										//image.setRGB(drawX + object.getSizeY() * MAP_SCALE/2, drawY - MAP_SCALE, 0xFFFFFF);
									}
								}
							}
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

	private int packHsl(int var0, int var1, int var2)
	{
		if (var2 > 179) var1 /= 2;
		if (var2 > 192) var1 /= 2;
		if (var2 > 217) var1 /= 2;
		if (var2 > 243) var1 /= 2;

		int var3 = (var1 / 32 << 7) + (var0 / 4 << 10) + var2 / 2;
		return var3;
	}

	static int method1792(int var0, int var1)
	{
		if (var0 == -1) return 12345678;

		var1 = (var0 & 127) * var1 / 128;
		if (var1 < 2) var1 = 2;
		else if (var1 > 126) var1 = 126;

		return (var0 & 65408) + var1;
	}

	static final int adjustHSLListness0(int var0, int var1)
	{
		if (var0 == -2) return 12345678;

		if (var0 == -1)
		{
			if (var1 < 2) var1 = 2;
			else if (var1 > 126) var1 = 126;

			return var1;
		}

		var1 = (var0 & 127) * var1 / 128;
		if (var1 < 2) var1 = 2;
		else if (var1 > 126) var1 = 126;

		return (var0 & 65408) + var1;
	}

	private void drawMapSquare(int[][] pixels, int x, int y, int rgb)
	{
		x *= MAP_SCALE;
		y *= MAP_SCALE;

		for (int i = 0; i < MAP_SCALE; ++i)
			for (int j = 0; j < MAP_SCALE; ++j)
				pixels[x + i][y + j] = rgb;
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

	private void loadUnderlays(Store store) throws IOException
	{
		Storage storage = store.getStorage();
		Index index = store.getIndex(IndexType.CONFIGS);
		Archive archive = index.getArchive(ConfigType.UNDERLAY.getId());

		byte[] archiveData = storage.loadArchive(archive);
		ArchiveFiles files = archive.getFiles(archiveData);

		for (FSFile file : files.getFiles())
		{
			UnderlayLoader loader = new UnderlayLoader();
			UnderlayDefinition underlay = loader.load(file.getFileId(), file.getContents());

			underlays.put(underlay.getId(), underlay);
		}
	}

	private UnderlayDefinition findUnderlay(int id)
	{
		return underlays.get(id);
	}

	private void loadOverlays(Store store) throws IOException
	{
		Storage storage = store.getStorage();
		Index index = store.getIndex(IndexType.CONFIGS);
		Archive archive = index.getArchive(ConfigType.OVERLAY.getId());

		byte[] archiveData = storage.loadArchive(archive);
		ArchiveFiles files = archive.getFiles(archiveData);

		for (FSFile file : files.getFiles())
		{
			OverlayLoader loader = new OverlayLoader();
			OverlayDefinition overlay = loader.load(file.getFileId(), file.getContents());

			overlays.put(overlay.getId(), overlay);
		}
	}

	private OverlayDefinition findOverlay(int id)
	{
		return overlays.get(id);
	}

	private void loadSprites() throws IOException
	{
		Storage storage = store.getStorage();
		Index index = store.getIndex(IndexType.SPRITES);
		Archive a = index.findArchiveByName("mapscene");
		byte[] contents = a.decompress(storage.loadArchive(a));

		SpriteLoader loader = new SpriteLoader();
		SpriteDefinition[] mapDecorations = loader.load(a.getArchiveId(), contents);
	}
}
