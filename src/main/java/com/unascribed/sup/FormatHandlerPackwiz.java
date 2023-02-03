package com.unascribed.sup;

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import com.grack.nanojson.JsonArray;
import com.grack.nanojson.JsonObject;
import com.moandjiezana.toml.Toml;
import com.unascribed.flexver.FlexVerComparator;
import com.unascribed.sup.FlavorGroup.FlavorChoice;
import com.unascribed.sup.PuppetHandler.AlertMessageType;
import com.unascribed.sup.PuppetHandler.AlertOption;
import com.unascribed.sup.PuppetHandler.AlertOptionType;

public class FormatHandlerPackwiz extends FormatHandler {
	
	private static final boolean changeFlavors = Boolean.getBoolean("unsup.packwiz.changeFlavors");
	
	static CheckResult check(URL src) throws IOException {
		Agent.log("WARN", "Loading packwiz-format manifest from "+src+" - this functionality is experimental!");
		Version ourVersion = Version.fromJson(Agent.state.getObject("current_version"));
		Toml pack = IOHelper.loadToml(src, 4*K, new URL(src, "unsup.sig"));
		String fmt = pack.getString("pack-format");
		if (!fmt.equals("unsup-packwiz") && (!fmt.startsWith("packwiz:") || FlexVerComparator.compare("packwiz:1.1.0", fmt) < 0))
			throw new IOException("Cannot read unknown pack-format "+fmt);
		JsonObject pwstate = Agent.state.getObject("packwiz");
		if (pwstate == null) {
			pwstate = new JsonObject();
			Agent.state.put("packwiz", pwstate);
		}
		Toml indexMeta = pack.getTable("index");
		if (indexMeta == null)
			throw new IOException("Malformed pack.toml: [index] table is missing");
		HashFunction indexFunc = parseFunc(indexMeta.getString("hash-format"));
		String indexDoublet = indexFunc+":"+indexMeta.getString("hash");
		boolean actualUpdate = !indexDoublet.equals(pwstate.getString("lastIndexHash"));
		if (changeFlavors || actualUpdate) {
			if (ourVersion == null) {
				ourVersion = new Version("null", 0);
			}
			Version theirVersion = new Version(pack.getString("version"), ourVersion.code+1);
			JsonObject newState = new JsonObject(Agent.state);
			pwstate = new JsonObject(pwstate);
			newState.put("packwiz", pwstate);
			
			Agent.log("INFO", "Update available - our index state is "+pwstate.getString("lastIndexHash")+", theirs is "+indexDoublet);
			String interlude = " from "+ourVersion.name+" to "+theirVersion.name;
			if (ourVersion.name.equals(theirVersion.name)) {
				interlude = "";
			}
			boolean bootstrapping = !pwstate.containsKey("lastIndexHash");
			if (!bootstrapping && actualUpdate) {
				AlertOption updateResp = PuppetHandler.openAlert("Update available",
						"<b>An update"+interlude+" is available!</b><br/>Do you want to install it?",
						AlertMessageType.QUESTION, AlertOptionType.YES_NO, AlertOption.YES);
				if (updateResp == AlertOption.NO) {
					Agent.log("INFO", "Ignoring update by user choice.");
					return new CheckResult(ourVersion, theirVersion, null);
				}
			}
			pwstate.put("lastIndexHash", indexDoublet);
			PuppetHandler.updateTitle(bootstrapping ? "Bootstrapping..." : "Updating...", false);
			PuppetHandler.updateSubtitle("Calculating update");
			Toml index = IOHelper.loadToml(new URL(src, indexMeta.getString("file")), 64*K,
					indexFunc, indexMeta.getString("hash"));
			Toml unsup = null;
			List<FlavorGroup> unpickedGroups = new ArrayList<>();
			Map<String, FlavorGroup> syntheticGroups = new HashMap<>();
			for (Map.Entry<String, Object> en : pwstate.getObject("syntheticFlavorGroups", new JsonObject()).entrySet()) {
				if (en.getValue() instanceof JsonObject) {
					JsonObject obj = (JsonObject)en.getValue();
					String id = obj.getString("id");
					if (id == null) continue;
					String name = obj.getString("name");
					String description = obj.getString("description");
					JsonArray choices = obj.getArray("choices");
					FlavorGroup grp = new FlavorGroup();
					grp.id = id;
					grp.name = name;
					grp.description = description;
					for (Object cele : choices) {
						FlavorChoice c = new FlavorChoice();
						if (cele instanceof JsonObject) {
							JsonObject cobj = (JsonObject)cele;
							c.id = cobj.getString("id");
							if (c.id == null) continue;
							c.name = cobj.getString("name");
							c.description = cobj.getString("description");
						}
					}
					syntheticGroups.put(en.getKey(), grp);
				}
			}
			Map<String, List<String>> metafileFlavors = new HashMap<>();
			JsonArray ourFlavors = Agent.state.getArray("flavors");
			if (ourFlavors == null) ourFlavors = new JsonArray();
			if (pack.containsTable("versions") && pack.getTable("versions").containsPrimitive("unsup")) {
				unsup = IOHelper.loadToml(new URL(src, "unsup.toml"), 64*K, null);
				if (unsup.containsTable("flavor_groups")) {
					flavors: for (Map.Entry<String, Object> en : unsup.getTable("flavor_groups").entrySet()) {
						if (en.getValue() instanceof Toml) {
							Toml group = (Toml)en.getValue();
							String groupId = en.getKey();
							String groupName = group.getString("name", groupId);
							String groupDescription = group.getString("description", "No description");
							String defChoice = Agent.config.get("flavors."+groupId);
							FlavorGroup grp = new FlavorGroup();
							grp.id = groupId;
							grp.name = groupName;
							grp.description = groupDescription;
							if (group.containsTableArray("choices")) {
								for (Object o : group.getList("choices")) {
									String id, name, description;
									if (o instanceof Map) {
										Map<String, Object> choice = (Map<String, Object>)o;
										id = String.valueOf(choice.get("id"));
										name = String.valueOf(choice.getOrDefault("name", id));
										description = String.valueOf(choice.getOrDefault("description", ""));
									} else {
										id = String.valueOf(o);
										name = id;
										description = "";
									}
									if (!changeFlavors && Util.iterableContains(ourFlavors, id)) {
										// a choice has already been made for this flavor
										continue flavors;
									}
									FlavorGroup.FlavorChoice c = new FlavorGroup.FlavorChoice();
									c.id = id;
									c.name = name;
									c.description = description;
									c.def = changeFlavors ? Util.iterableContains(ourFlavors, id) : id.equals(defChoice);
									if (c.def) {
										grp.defChoice = c.id;
										grp.defChoiceName = c.name;
									}
									grp.choices.add(c);
								}
							}
							unpickedGroups.add(grp);
						}
					}
				}
				if (unsup.containsTable("metafile")) {
					for (Map.Entry<String, Object> en : unsup.getTable("metafile").entrySet()) {
						if (en.getValue() instanceof Toml) {
							Toml t = (Toml)en.getValue();
							if (t.contains("flavors")) {
								if (t.containsTableArray("flavors")) {
									metafileFlavors.put(en.getKey(), t.getList("flavors").stream()
											.map(String::valueOf)
											.collect(Collectors.toList()));
								} else {
									metafileFlavors.put(en.getKey(), Collections.singletonList(t.getString("flavors")));
								}
							}
						}
					}
				}
			}
			UpdatePlan<FilePlan> plan = new UpdatePlan<>(bootstrapping, ourVersion.name, theirVersion.name, newState);
			Set<String> toDelete = new HashSet<>();
			JsonObject lastState = pwstate.getObject("lastState");
			if (lastState != null) {
				for (Map.Entry<String, Object> en : lastState.entrySet()) {
					toDelete.add(en.getKey());
					String v = String.valueOf(en.getValue());
					String[] s = v.split(":", 2);
					plan.expectedState.put(en.getKey(), new FileState(HashFunction.byName(s[0]), s[1], -1));
				}
			}
			JsonObject metafileState = pwstate.getObject("metafileState");
			if (metafileState == null) {
				metafileState = new JsonObject();
				pwstate.put("metafileState", metafileState);
			}
			JsonObject metafileFiles = pwstate.getObject("metafileFiles");
			if (metafileFiles == null) {
				metafileFiles = new JsonObject();
				pwstate.put("metafileFiles", metafileFiles);
			}
			class Metafile {
				final String name;
				final String path;
				final String hash;
				final Toml toml;
				String target;
				
				public Metafile(String name, String path, String hash, Toml metafile) {
					this.name = name;
					this.path = path;
					this.hash = hash;
					this.toml = metafile;
				}
			}
			ExecutorService svc = Executors.newFixedThreadPool(12);
			List<Future<Metafile>> metafileFutures = new ArrayList<>();
			Map<String, FileState> postState = new HashMap<>();
			HashFunction func = parseFunc(index.getString("hash-format"));
			for (Toml file : index.getTables("files")) {
				String path = file.getString("file");
				String hash = file.getString("hash");
				if (file.getBoolean("metafile", false)) {
					String name = path.substring(path.lastIndexOf('/')+1, path.endsWith(".pw.toml") ? path.length()-8 : path.length());
					String metafileDoublet = (func+":"+hash);
					if (metafileDoublet.equals(metafileState.getString(path)) && !changeFlavors) {
						toDelete.remove(String.valueOf(metafileFiles.get(path)));
						continue;
					}
					metafileFutures.add(svc.submit(() -> {
						return new Metafile(name, path, hash, IOHelper.loadToml(new URL(src, path), 8*K, func, hash));
					}));
				} else {
					FilePlan f = new FilePlan();
					f.state = new FileState(func, hash, -1);
					f.url = new URL(src, path);
					toDelete.remove(path);
					postState.put(path, f.state);
					if (!plan.expectedState.containsKey(path)) {
						plan.expectedState.put(path, FileState.EMPTY);
					} else if (plan.expectedState.get(path).equals(f.state)) {
						continue;
					}
					plan.files.put(path, f);
				}
			}
			svc.shutdown();
			PuppetHandler.updateSubtitle("Retrieving metafiles");
			PuppetHandler.updateTitle(bootstrapping ? "Bootstrapping..." : "Updating...", false);
			for (Future<Metafile> future : metafileFutures) {
				Metafile mf;
				while (true) {
					try {
						mf = future.get();
						break;
					} catch (InterruptedException e) {
					} catch (ExecutionException e) {
						for (Future<?> f2 : metafileFutures) {
							try {
								f2.cancel(false);
							} catch (Throwable t) {}
						}
						if (e.getCause() instanceof IOException) throw (IOException)e.getCause();
						throw new RuntimeException(e);
					}
				}
				String path = mf.path;
				String hash = mf.hash;
				Toml metafile = mf.toml;
				String side = metafile.getString("side");
				String metafileDoublet = (func+":"+hash);
				metafileState.put(path, metafileDoublet);
				if (side != null && !side.equals("both") && !side.equals(Agent.detectedEnv)) {
					Agent.log("INFO", "Skipping "+path+" as it's not eligible for env "+Agent.detectedEnv);
					continue;
				}
				path = path.replace("\\", "/");
				String pfx;
				int slash = path.lastIndexOf('/');
				if (slash >= 0) {
					pfx = path.substring(0, slash+1);
				} else {
					pfx = "";
				}
				mf.target = pfx+metafile.getString("filename");
				Toml option = metafile.getTable("option");
				syntheticGroups.remove(mf.name);
				if (option != null && option.getBoolean("optional", false) && !metafileFlavors.containsKey(mf.name)) {
					FlavorGroup synth = new FlavorGroup();
					synth.id = mf.name;
					synth.name = metafile.getString("name");
					synth.description = option.getString("description", "No description");
					boolean defOn = changeFlavors ? Util.iterableContains(ourFlavors, mf.name+"_on") : option.getBoolean("default", false);
					FlavorGroup.FlavorChoice on = new FlavorGroup.FlavorChoice();
					on.id = mf.name+"_on";
					on.name = "On";
					on.def = defOn;
					synth.choices.add(on);
					FlavorGroup.FlavorChoice off = new FlavorGroup.FlavorChoice();
					off.id = mf.name+"_off";
					off.name = "Off";
					off.def = !defOn;
					synth.choices.add(off);
					metafileFlavors.put(mf.name, Collections.singletonList(on.id));
					syntheticGroups.put(mf.name, synth);
				}
			}

			JsonObject syntheticGroupsJson = new JsonObject();
			pwstate.put("syntheticFlavorGroups", syntheticGroupsJson);
			for (Map.Entry<String, FlavorGroup> en : syntheticGroups.entrySet()) {
				if (changeFlavors || !en.getValue().choices.stream().map(c -> c.id).anyMatch(ourFlavors::contains)) {
					unpickedGroups.add(en.getValue());
				}
				FlavorGroup grp = en.getValue();
				JsonObject obj = new JsonObject();
				obj.put("id", grp.id);
				obj.put("name", grp.name);
				obj.put("description", grp.description);
				JsonArray choices = new JsonArray();
				for (FlavorChoice c : grp.choices) {
					JsonObject cobj = new JsonObject();
					cobj.put("id", c.id);
					cobj.put("name", c.name);
					cobj.put("description", c.description);
					choices.add(cobj);
				}
				obj.put("choices", choices);
				syntheticGroupsJson.put(en.getKey(), obj);
			}

			PuppetHandler.updateSubtitle("Waiting for flavor selection");
			if (changeFlavors) {
				ourFlavors.clear();
			}
			ourFlavors = handleFlavorSelection(ourFlavors, unpickedGroups, newState);
			
			for (Future<Metafile> future : metafileFutures) {
				Metafile mf;
				try {
					mf = future.get();
				} catch (Throwable e) {
					throw new AssertionError(e);
				}
				

				if (!Util.iterablesIntersect(metafileFlavors.get(mf.name), ourFlavors)) {
					Agent.log("INFO", "Skipping "+mf.target+" as it's not eligible for our selected flavors");
					continue;
				}
				
				String mfpath = mf.path;
				String path = mf.target;
				Toml metafile = mf.toml;
				
				metafileFiles.put(mfpath, path);
				toDelete.remove(path);
				Toml download = metafile.getTable("download");
				FilePlan f = new FilePlan();
				HashFunction thisFunc = parseFunc(download.getString("hash-format"));
				String thisHash = download.getString("hash");
				if (thisFunc == HashFunction.MURMUR2_CF) {
					thisHash = Murmur2MessageDigest.decToHex(thisHash);
				}
				f.state = new FileState(thisFunc, thisHash, -1);
				postState.put(path, f.state);
				if (!plan.expectedState.containsKey(path)) {
					plan.expectedState.put(path, FileState.EMPTY);
				} else if (plan.expectedState.get(path).equals(f.state)) {
					continue;
				}
				String url = download.getString("url");
				if (url != null) {
					f.url = new URL(url);
				} else {
					String mode = download.getString("mode");
					if (Util.b64Str("bWV0YWRhdGE6Y3Vyc2Vmb3JnZQ==").equals(mode)) {
						// Not a virus. Trust me, I'm a dolphin
						Toml tbl = metafile.getTable(Util.b64Str("dXBkYXRlLmN1cnNlZm9yZ2U="));
						f.hostile = true;
						String str = Long.toString(tbl.getLong(Util.b64Str("ZmlsZS1pZA==")));
						int i = (str.length()+1)/2;
						String l = str.substring(0, i);
						String r = str.substring(i);
						while (r.startsWith("0")) r = r.substring(1);
						f.url = new URL(String.format(Util.b64Str("aHR0cHM6Ly9tZWRpYWZpbGV6LmZvcmdlY2RuLm5ldC9maWxlcy8lcy8lcy8lcw=="),
								l, r, metafile.getString(Util.b64Str("ZmlsZW5hbWU=")).replace("+", "%2B")));
					} else {
						throw new IOException("Cannot update "+path+" - unrecognized download mode "+mode);
					}
				}
				plan.files.put(path, f);
			}
			for (String path : toDelete) {
				FilePlan f = new FilePlan();
				f.state = FileState.EMPTY;
				plan.files.put(path, f);
				postState.put(path, FileState.EMPTY);
			}
			lastState = new JsonObject();
			for (Map.Entry<String, FileState> en : postState.entrySet()) {
				if (en.getValue().hash == null) continue;
				lastState.put(en.getKey(), en.getValue().func+":"+en.getValue().hash);
			}
			pwstate.put("lastState", lastState);
			return new CheckResult(ourVersion, theirVersion, plan);
		} else {
			Agent.log("INFO", "We appear to be up-to-date. Nothing to do");
		}
		return new CheckResult(ourVersion, ourVersion, null);
	}
	
	@SuppressWarnings("deprecation")
	static HashFunction parseFunc(String str) {
		switch (str) {
			case "md5": return HashFunction.MD5;
			case "sha1": return HashFunction.SHA1;
			case "sha256": return HashFunction.SHA2_256;
			case "sha384": return HashFunction.SHA2_384;
			case "sha512": return HashFunction.SHA2_512;
			case "murmur2": return HashFunction.MURMUR2_CF;
			default: throw new IllegalArgumentException("Unknown packwiz hash function "+str);
		}
	}
	
}
