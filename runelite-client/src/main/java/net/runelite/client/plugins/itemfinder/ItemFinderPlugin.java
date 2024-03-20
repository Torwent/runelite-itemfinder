/*
 * Copyright (c) 2016-2018, Seth <Sethtroll3@gmail.com>
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
package net.runelite.client.plugins.itemfinder;

import com.google.common.hash.Hashing;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.task.Schedule;
import org.apache.commons.lang3.tuple.MutableTriple;

import javax.imageio.ImageIO;
import javax.inject.Inject;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.awt.image.DataBufferInt;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@PluginDescriptor(
		name = "ItemFinder"
)

public class ItemFinderPlugin extends Plugin {
	@Inject
	private ItemManager itemManager;
	@Inject
	private Client client;
	@Inject
	private ClientThread clientThread;

	private boolean SameImages(BufferedImage img1, BufferedImage img2) {
		if (img1.getWidth() == img2.getWidth() && img1.getHeight() == img2.getHeight()) {
			for (int x = 0; x < img1.getWidth(); x++) {
				for (int y = 0; y < img1.getHeight(); y++) {
					if (img1.getRGB(x, y) != img2.getRGB(x, y))
						return false;
				}
			}
		} else {
			return false;
		}
		return true;
	}

	ArrayList<MutableTriple<Integer, String, BufferedImage>> items = new ArrayList<MutableTriple<Integer, String, BufferedImage>>();
	ArrayList<MutableTriple<Integer, String, BufferedImage>> filteredItems = new ArrayList<MutableTriple<Integer, String, BufferedImage>>();

	private void LoadAll() {

		ItemFinderCache cache = new ItemFinderCache();
		cache.Load();
		for (int id = 0; id < client.getItemCount(); id++) {
			if (cache.ids.indexOf(id) == -1) {
				continue;
			}
			items.add(new MutableTriple(id, cache.names.get(cache.ids.indexOf(id)), itemManager.getImage(id, 0, false)));
		}
	}

	private void Filter() {
		for (int i = 0; i < items.size(); i++) {
			boolean isDuplicate = false;
			for (int j = 0; j < filteredItems.size(); j++) {
				if (items.get(i).middle.equalsIgnoreCase(filteredItems.get(j).middle) && SameImages(items.get(i).right, filteredItems.get(j).right)) {
					isDuplicate = true;
					break;
				}
			}

			if (!isDuplicate) {
				filteredItems.add(items.get(i));
			}
		}
	}

	private boolean dumped = false;

	@Schedule(
			period = 3,
			unit = ChronoUnit.SECONDS
	)
	public void Dump() {
		if ((dumped) || (client.getGameState() != GameState.LOGIN_SCREEN)) {
			return;
		}
		System.out.println("ItemFinder starting...");
		clientThread.invoke(() ->
		{
			try {
				System.out.println("ItemFinder...");
				String dir = Paths.get(System.getProperty("user.dir") + File.separator + "itemfinder" + File.separator).toString();

				if (!Files.isDirectory(Paths.get(dir))) {
					Files.createDirectory(Paths.get(dir));
				}

				System.out.println("Saving to " + dir);

				System.out.println("Loading items...");
				LoadAll();
				System.out.println("Loaded " + items.size() + " items");

				System.out.println("Filtering items...");
				Filter();
				System.out.println("Filtered item count: " + filteredItems.size());

				ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(new File(dir, "images.zip"))));

				FileWriter item = new FileWriter(new File(dir, "item"));
				FileWriter id = new FileWriter(new File(dir, "id"));

				for (int i = 0; i < filteredItems.size(); i++) {
					zip.putNextEntry(new ZipEntry(filteredItems.get(i).left + ".png"));
					ImageIO.write(filteredItems.get(i).right, "png", zip);

					item.write(filteredItems.get(i).middle + System.lineSeparator());
					id.write( filteredItems.get(i).left + System.lineSeparator());
				}

				zip.close();
				item.close();
				id.close();

				System.out.println("ItemFinder completed");
			} catch (Exception e) {
				System.out.println("ItemFinder exception:");
				System.out.println(e);
			}
		});

		dumped = true;
	}
}
