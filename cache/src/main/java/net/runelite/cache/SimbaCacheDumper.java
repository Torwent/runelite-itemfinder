/*
 * Copyright (c) 2017, Adam <Adam@sigterm.info>
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

import net.runelite.cache.definitions.ItemDefinition;
import net.runelite.cache.definitions.ModelDefinition;
import net.runelite.cache.definitions.loaders.ModelLoader;
import net.runelite.cache.definitions.providers.ModelProvider;
import net.runelite.cache.fs.Archive;
import net.runelite.cache.fs.Index;
import net.runelite.cache.fs.Store;
import net.runelite.cache.item.ItemSpriteFactory;
import net.runelite.cache.region.Region;
import net.runelite.cache.util.XteaKeyManager;
import org.apache.commons.cli.*;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class SimbaCacheDumper
{
	public static void main(String[] args) throws IOException
	{
		Options options = new Options();

		options.addOption(Option.builder("p").longOpt("path").hasArg().required().build());
		options.addOption(Option.builder("c").longOpt("cache").hasArg().required().build());
		options.addOption(Option.builder("o").longOpt("output").hasArg().required().build());

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

		final String mainDir = cmd.getOptionValue("path");
		final String cacheName = cmd.getOptionValue("cache");

		final String cacheDirectory = mainDir + File.separator + cacheName + File.separator + "cache";
		final String xteaJSONPath = mainDir + File.separator + cacheName + File.separator + cacheName.replace("cache-", "keys-") + ".json";

		final String outputDirectory = cmd.getOptionValue("output") + File.separator + cacheName;

		XteaKeyManager xteaKeyManager = new XteaKeyManager();
		try (FileInputStream fin = new FileInputStream(xteaJSONPath))
		{
			xteaKeyManager.loadKeys(fin);
		}

		File outDir = new File(outputDirectory);
		if (!outDir.mkdirs()) throw new RuntimeException("Failed to create output path: " + outDir.getPath());

		Store store = loadStore(cacheDirectory);

		dumpMap(store, outDir, xteaKeyManager);
		dumpCollision(store, outDir, xteaKeyManager);
		dumpHeight(store, outDir, xteaKeyManager);
		dumpObjects(store, outDir, xteaKeyManager);
	}

	private static Store loadStore(String cache) throws IOException
	{
		Store store = new Store(new File(cache));
		store.load();
		return store;
	}

	private static void dumpMap(Store store, File outDir, XteaKeyManager xteaKeyManager) throws IOException
	{
		System.out.println("Dumping map images in map.zip");
		SimbaMapImageDumper.exportFullMap = false;
		SimbaMapImageDumper dumper = new SimbaMapImageDumper(store, xteaKeyManager);
		dumper.load();

		ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(new File(outDir, "map.zip"))));
		for (int i = 0; i < Region.Z; ++i) dumper.drawRegions(i, zip);
		zip.close();
	}

	private static void dumpCollision(Store store, File outDir, XteaKeyManager xteaKeyManager) throws IOException
	{
		System.out.println("Dumping map images in collision.zip");
		SimbaCollisionMapDumper.exportFullMap = false;
		SimbaCollisionMapDumper dumper = new SimbaCollisionMapDumper(store, xteaKeyManager);
		dumper.load();

		ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(new File(outDir, "collision.zip"))));
		for (int i = 0; i < Region.Z; ++i) dumper.drawRegions(i, zip);
		zip.close();
	}

	private static void dumpHeight(Store store, File outDir, XteaKeyManager xteaKeyManager) throws IOException
	{
		System.out.println("Dumping map images in heightmap.zip");
		SimbaHeightMapDumper.exportFullMap = false;
		SimbaHeightMapDumper dumper = new SimbaHeightMapDumper(store);
		dumper.load(xteaKeyManager);

		ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(new File(outDir, "heightmap.zip"))));
		dumper.drawRegions(0, zip);
		zip.close();
	}

	private static void dumpObjects(Store store, File outDir, XteaKeyManager xteaKeyManager) throws IOException
	{
		System.out.println("Dumping map images in objects.zip");
		SimbaObjectInfoDumper.exportFullMap = false;
		SimbaObjectInfoDumper dumper = new SimbaObjectInfoDumper(store, xteaKeyManager);
		dumper.load();

		ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(new File(outDir, "objects.zip"))));
		for (int i = 0; i < Region.Z; ++i) dumper.mapRegions(i, zip);
		zip.close();
	}

	private static MessageDigest md5;

	private static byte[] hashImage(BufferedImage img) throws IOException {
		ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
		ImageIO.write(img, "png", outputStream);
		md5.update(outputStream.toByteArray());

		return md5.digest();
	}

	private static void dumpItemImage(ItemManager itemManager, ModelProvider modelProvider, SpriteManager spriteManager, TextureManager textureManager, Integer id, String name, ZipOutputStream zipper, FileWriter itemFile, ArrayList<byte[]> hashes, ArrayList<String> names) throws IOException {

		BufferedImage img = ItemSpriteFactory.createSprite(itemManager, modelProvider, spriteManager, textureManager, id, 1, 1, 3153952, false);
		byte[] hash = hashImage(img);

		for (int i = 0; i < hashes.size(); i++) {
			if (names.get(i).equals(name) && md5.isEqual(hashes.get(i), hash)) {
				System.out.println("Duplicate: " + name + " :: " + id);
				return;
			}
		}
		hashes.add(hash);
		names.add(name);

		zipper.putNextEntry(new ZipEntry(id + ".png"));

		ImageIO.write(img, "png", zipper);
		itemFile.write(name + "=" + id + System.lineSeparator());
	}

	private static void dumpItemFinder(Store store, File outDir) throws IOException {
		try {
			md5 = MessageDigest.getInstance("MD5");
		} catch (Exception ex) {
			return;
		}

		ArrayList<String> names = new ArrayList<String>();
		ArrayList<byte[]> hashes = new ArrayList<byte[]>();

		ZipOutputStream zipper = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(new File(outDir, "item-images.zip"))));
		FileWriter itemFile = new FileWriter(new File(outDir, "item-names"));

		ItemManager itemManager = new ItemManager(store);
		itemManager.load();
		itemManager.link();

		ModelProvider modelProvider = new ModelProvider()
		{
			@Override
			public ModelDefinition provide(int modelId) throws IOException
			{
				Index models = store.getIndex(IndexType.MODELS);
				Archive archive = models.getArchive(modelId);

				byte[] data = archive.decompress(store.getStorage().loadArchive(archive));
				ModelDefinition inventoryModel = new ModelLoader().load(modelId, data);
				return inventoryModel;
			}
		};

		SpriteManager spriteManager = new SpriteManager(store);
		spriteManager.load();

		TextureManager textureManager = new TextureManager(store);
		textureManager.load();

		for (ItemDefinition itemDef : itemManager.getItems())
		{
			if ((itemDef.name == null) || (itemDef.name.isEmpty())) {
				continue;
			}
			if (itemDef.name.equalsIgnoreCase("null") && (itemDef.getNotedID() == -1))  {
				continue;
			}

			String name = "";
			if ((itemDef.getNotedTemplate() == -1) && (!itemDef.getName().equalsIgnoreCase("null"))) {
				name = itemDef.getName().toLowerCase();
			}
			else if (itemDef.getNotedID() != -1) {
				name = "noted " + itemManager.getItem(itemDef.getNotedID()).getName().toLowerCase();
			}

			// stacked items
			if (itemDef.getCountObj() != null) {
				for (int i = 0; i < 10; ++i) {
					int id = itemDef.getCountObj()[i];

					if (id > 0) {
						try {
							dumpItemImage(itemManager, modelProvider, spriteManager, textureManager, id, name, zipper, itemFile, hashes, names);
						}
						catch (Exception ex) {
							System.out.println("Error dumping noted item " + id);
							System.out.println(ex);
						}
					}
				}
			}

			try {
				dumpItemImage(itemManager, modelProvider, spriteManager, textureManager, itemDef.id, name, zipper, itemFile, hashes, names);
			} catch (Exception ex) {
				System.out.println("Error dumping item " + itemDef.id);
				System.out.println(ex);
			}
		}

		zipper.close();
		itemFile.close();
	}
}
