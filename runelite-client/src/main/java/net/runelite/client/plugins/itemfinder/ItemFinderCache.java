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

import net.runelite.cache.ItemManager;
import net.runelite.cache.definitions.ItemDefinition;
import net.runelite.cache.fs.Store;

import java.io.File;
import java.nio.file.Paths;
import java.util.ArrayList;

// Use cache to list items because getCountObj isn't available in the client.
public class ItemFinderCache
{

	ArrayList<Integer> ids = new ArrayList<Integer>();
	ArrayList<String> names = new ArrayList<String>();

	public void Load() {

		try {
			String cache = Paths.get(System.getProperty("user.home"), ".runelite", "jagexcache").toString();
			Store store = new Store(new File(cache + File.separator + "oldschool" + File.separator + "LIVE"));
			store.load();

			ItemManager itemManager = new ItemManager(store);
			itemManager.load();
			itemManager.link();

			for (ItemDefinition itemDef : itemManager.getItems()) {
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

				ids.add(itemDef.id);
				names.add(name);

				if (itemDef.getCountObj() != null) {
					for (int i = 0; i < 10; ++i) {
						int id = itemDef.getCountObj()[i];

						if (id > 0) {
							ids.add(id);
							names.add(name);
						}
					}
				}
			}

		} catch (Exception e) {
			System.out.println(e);
			System.out.println("Fatal exception!");
		}
	}
}
