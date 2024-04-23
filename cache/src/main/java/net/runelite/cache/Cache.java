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

import java.awt.image.BufferedImage;
import java.io.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import net.runelite.cache.definitions.ItemDefinition;
import net.runelite.cache.definitions.ModelDefinition;
import net.runelite.cache.definitions.loaders.ModelLoader;
import net.runelite.cache.definitions.providers.ModelProvider;
import net.runelite.cache.fs.Archive;
import net.runelite.cache.fs.Index;
import net.runelite.cache.fs.Store;
import net.runelite.cache.item.ItemSpriteFactory;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;

import javax.imageio.ImageIO;

public class Cache
{
	public static void main(String[] args) throws IOException
	{
		Options options = new Options();

		options.addOption("c", "cache", true, "cache base");

		options.addOption(null, "items", true, "directory to dump items to");
		options.addOption(null, "npcs", true, "directory to dump npcs to");
		options.addOption(null, "objects", true, "directory to dump objects to");
		options.addOption(null, "sprites", true, "directory to dump sprites to");

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

		String cache = cmd.getOptionValue("cache");

		Store store = loadStore(cache);

		if (cmd.hasOption("items"))
		{
			String itemdir = cmd.getOptionValue("items");

			if (itemdir == null)
			{
				System.err.println("Item directory must be specified");
				return;
			}

			System.out.println("Dumping items to " + itemdir);
			dumpItems(store, new File(itemdir));
		}
		else if (cmd.hasOption("npcs"))
		{
			String npcdir = cmd.getOptionValue("npcs");

			if (npcdir == null)
			{
				System.err.println("NPC directory must be specified");
				return;
			}

			System.out.println("Dumping npcs to " + npcdir);
			dumpNpcs(store, new File(npcdir));
		}
		else if (cmd.hasOption("objects"))
		{
			String objectdir = cmd.getOptionValue("objects");

			if (objectdir == null)
			{
				System.err.println("Object directory must be specified");
				return;
			}

			System.out.println("Dumping objects to " + objectdir);
			dumpObjects(store, new File(objectdir));
		}
		else if (cmd.hasOption("sprites"))
		{
			String spritedir = cmd.getOptionValue("sprites");

			if (spritedir == null)
			{
				System.err.println("Sprite directory must be specified");
				return;
			}

			System.out.println("Dumping sprites to " + spritedir);
			dumpSprites(store, new File(spritedir));
		}
		else
		{
			System.err.println("Nothing to do");
		}
	}

	private static Store loadStore(String cache) throws IOException
	{
		Store store = new Store(new File(cache));
		store.load();
		return store;
	}

	private static void dumpItems(Store store, File itemdir) throws IOException
	{
		ItemManager dumper = new ItemManager(store);
		dumper.load();
		dumper.export(itemdir);
		dumper.java(itemdir);
	}

	private static void dumpNpcs(Store store, File npcdir) throws IOException
	{
		NpcManager dumper = new NpcManager(store);
		dumper.load();
		dumper.dump(npcdir);
		dumper.java(npcdir);
	}

	private static void dumpObjects(Store store, File objectdir) throws IOException
	{
		ObjectManager dumper = new ObjectManager(store);
		dumper.load();
		dumper.dump(objectdir);
		dumper.java(objectdir);
	}

	private static void dumpSprites(Store store, File spritedir) throws IOException
	{
		SpriteManager dumper = new SpriteManager(store);
		dumper.load();
		dumper.export(spritedir);
	}

	private static MessageDigest md5;

	private static byte[] hashImage(BufferedImage img) throws IOException {
		ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
		ImageIO.write(img, "png", outputStream);
		md5.update(outputStream.toByteArray());

		return md5.digest();
	}

	private static void dumpItemImage(ItemManager itemManager, ModelProvider modelProvider, SpriteManager spriteManager, TextureManager textureManager, Integer id, String name, ZipOutputStream zipper, FileWriter itemFile, ArrayList<byte[]> hashes, ArrayList<String> names) throws IOException, NoSuchAlgorithmException {

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

		try {
			zipper.putNextEntry(new ZipEntry(id + ".png"));
		} catch (Exception ex) {
			return;
		}

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
