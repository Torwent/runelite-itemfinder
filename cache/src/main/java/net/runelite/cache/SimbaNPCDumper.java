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

import com.google.gson.JsonObject;
import net.runelite.cache.definitions.ModelDefinition;
import net.runelite.cache.definitions.NpcDefinition;
import net.runelite.cache.definitions.exporters.NpcExporter;
import net.runelite.cache.definitions.loaders.ModelLoader;
import net.runelite.cache.definitions.loaders.NpcLoader;
import net.runelite.cache.fs.*;
import net.runelite.cache.models.ObjExporter;
import net.runelite.cache.util.IDClass;
import org.apache.commons.cli.*;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class SimbaNPCDumper
{
	private final Store store;
	private static Index index;
	private static TextureManager textureManager;
	private final ModelLoader modelLoader;
	private final Map<Integer, NpcDefinition> npcs = new HashMap<>();

	public SimbaNPCDumper(Store store) throws IOException {
		this.store = store;
		this.modelLoader = new ModelLoader();
	}

	public void load() throws IOException
	{
		NpcLoader loader = new NpcLoader();

		Storage storage = store.getStorage();
		Index configsIndex = store.getIndex(IndexType.CONFIGS);
		Archive archive = configsIndex.getArchive(ConfigType.NPC.getId());

		index = store.getIndex(IndexType.MODELS);
		textureManager = new TextureManager(store);
		textureManager.load();

		byte[] archiveData = storage.loadArchive(archive);
		ArchiveFiles files = archive.getFiles(archiveData);

		for (FSFile f : files.getFiles())
		{
			NpcDefinition def = loader.load(f.getFileId(), f.getContents());
			npcs.put(f.getFileId(), def);
		}
	}

	public Collection<NpcDefinition> getNPCs()
	{
		return Collections.unmodifiableCollection(npcs.values());
	}

	public NpcDefinition getNPC(int npcID)
	{
		return npcs.get(npcID);
	}

	public void export(File out) throws IOException
	{
		out.mkdirs();

		for (NpcDefinition def : npcs.values())
		{
			int height = 0;
			List<Integer> colors = new ArrayList<>();

			if (def.getModels() == null) continue;

			for (int i = 0; i < def.getModels().length; i++) {
				Archive archive = index.getArchive(def.getModels()[i]);
				byte[] contents = archive.decompress(store.getStorage().loadArchive(archive));
				ModelDefinition model = modelLoader.load(archive.getArchiveId(), contents);

				ObjExporter exporter = new ObjExporter(textureManager, model);
				if (height == 0) height = exporter.getSimbaHeight();
				colors.addAll(exporter.getSimbaColors());
			}

			NpcExporter exporter = new NpcExporter(def);

			File targ = new File(out, def.id + ".json");
			exporter.simbaExportTo(targ, height, colors);
		}
	}

	public void java(File java) throws IOException
	{
		java.mkdirs();
		try (IDClass ids = IDClass.create(java, "NPCID");
			IDClass nulls = IDClass.create(java, "NullNPCID"))
		{
			for (NpcDefinition def : npcs.values())
			{
				if (def.name.equalsIgnoreCase("NULL"))
				{
					nulls.add(def.name, def.id);
				}
				else
				{
					ids.add(def.name, def.id);
				}
			}
		}
	}

	public NpcDefinition provide(int npcID)
	{
		return getNPC(npcID);
	}

	private static void dumpNPCs(Store store, File npcdir) throws IOException
	{
		SimbaNPCDumper dumper = new SimbaNPCDumper(store);
		dumper.load();
		dumper.export(npcdir);
		dumper.java(npcdir);
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
		final String outputDirectory = cmd.getOptionValue("outputdir") + File.separator + cacheName + File.separator + "NPCs";

		File base = new File(cacheDirectory);
		File outDir = new File(outputDirectory);
		outDir.mkdirs();

		try (Store store = new Store(base))
		{
			store.load();

			System.out.println("Dumping NPCs to " + outDir);
			dumpNPCs(store, outDir);
		}
	}
}
