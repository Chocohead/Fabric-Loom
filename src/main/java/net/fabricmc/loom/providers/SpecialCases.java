/*
 * Copyright 2019, 2020 Chocohead
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package net.fabricmc.loom.providers;

import com.google.common.net.UrlEscapers;

import net.fabricmc.loom.LoomGradleExtension.JarMergeOrder;
import net.fabricmc.loom.util.MinecraftVersionInfo;
import net.fabricmc.loom.util.MinecraftVersionInfo.Downloads;

/** It's not hard coded, it's just inflexible */
class SpecialCases {
	private enum ServerType {
		RELEASE {
			@Override
			public String getDownload(String version) {
				return "Omniarchive/Miscellaneous Files/CURLs/maven.sk89q.com/minecraft-server-" + version + ".jar";
			}
		},
		BETA {
			@Override
			public String getDownload(String version) {
				return "Omniarchive/Java Edition/Servers/Beta/" + version + "/minecraft_server.jar";
			}
		},
		ALPHA {
			@Override
			public String getDownload(String version) {
				return "Omniarchive/Java Edition/Servers/Alpha/" + version.substring(1) + "/minecraft_server.jar";
			}
		};

		protected abstract String getDownload(String version);

		public String getDownloadURL(String version) {
			return "https://archive.org/download/20180501AoMCollection/Omniarchive.zip/".concat(getDownload(version));
		}
	}

	static void enhanceVersion(MinecraftVersionInfo version, JarMergeOrder mergeOrder) {
		fixMissingServer(version, mergeOrder);
	}

	private static void fixMissingServer(MinecraftVersionInfo version, JarMergeOrder mergeOrder) {
		if (version.downloads.containsKey("server")) return;

		ServerType type;
		String serverVersion, hash;
		switch (version.id) {
		case "1.2.4":
			type = ServerType.RELEASE;
			serverVersion = "1.2.4";
			hash = "ab5203296605bdb3a020b8b4ea6c8d03fd3a3e66";
			break;

		case "1.2.3":
			type = ServerType.RELEASE;
			serverVersion = "1.2.3";
			hash = "d0e0dab53361e16e154f5b6cba2a7d8e4323fb8e";
			break;

		case "1.2.2":
			type = ServerType.RELEASE;
			serverVersion = "1.2.2";
			hash = "673f50223b363c4c474d520c99104bd8481f0ce6";
			break;

		case "1.2.1":
			type = ServerType.RELEASE;
			serverVersion = "1.2.1";
			hash = "50bbca507680a248c4d8e965c800593dc5853026";
			break;

		case "1.1":
			type = ServerType.RELEASE;
			serverVersion = "1.1";
			hash = "3dbadea4e8a0923ac5fde9f4b9e98a244ea2589a";
			break;

		case "1.0": //Expects 1.0.0, although servers had an additional hot patch version too
			type = ServerType.RELEASE;
			serverVersion = "1.0.1";
			hash = "ecf944fcef1a630f95ded840de0059d4922f81c2";
			break;

		case "b1.8.1":
			type = ServerType.BETA;
			serverVersion = "b1.8.1";
			hash = "79c4f3dc88d06db359dbf5cfedbbbd120d73bf4f";
			break;

		case "b1.8":
			type = ServerType.BETA;
			serverVersion = "b1.8";
			hash = "8cdfd85eaebfb1f0c70fd4b2d5301a547e2d4caa";
			break;

		case "b1.7.3":
			type = ServerType.BETA;
			serverVersion = "b1.7.3";
			hash = "2f90dc1cb5ca7e9d71786801b307390a67fcf954";
			break;

		case "b1.7.2":
			type = ServerType.BETA;
			serverVersion = "b1.7.2";
			hash = "ee32a882e364c0021060853f5fcdf1b83e985904";
			break;

		case "b1.7_01":
			type = ServerType.BETA;
			serverVersion = "b1.7_01";
			hash = "2838451ebb627d71e5d245952a61e9d71e284f6c";
			break;

		case "b1.7":
			type = ServerType.BETA;
			serverVersion = "b1.7";
			hash = "0d841f7f651822120650a2e700832626f8206d89";
			break;

		case "b1.6.6":
			type = ServerType.BETA;
			serverVersion = "b1.6.6";
			hash = "e6c5db853f3b019599c24ce3d97b45bb7d8e02bd";
			break;

		case "b1.6.5":
			type = ServerType.BETA;
			serverVersion = "b1.6.5";
			hash = "781e69c6881626f77c9f168ae4f307ae1422552e";
			break;

		case "b1.6.4":
			type = ServerType.BETA;
			serverVersion = "b1.6.4";
			hash = "730f8d9484f8441816c89fa7093fc34bac98eb57";
			break;

		case "b1.6.3":
			type = ServerType.BETA;
			serverVersion = "b1.6.3";
			hash = "7a6bdde77bfa74e3ec24277ad3e72080e1f43d7e";
			break;

		case "b1.6.2":
			type = ServerType.BETA;
			serverVersion = "b1.6.2";
			hash = "130efd76373c39b87c6fe14215ed820d67e5278c";
			break;

		case "b1.6.1":
			type = ServerType.BETA;
			serverVersion = "b1.6.1";
			hash = "9dbf061d310e96bcd808a8b1705196fbd0b5a081";
			break;

		case "b1.6":
			type = ServerType.BETA;
			serverVersion = "b1.6";
			hash = "0adfbfb0ed16cce54c131d98fe50c911c86a9d9c";
			break;

		case "b1.5_01":
			type = ServerType.BETA;
			serverVersion = "b1.5_02";
			hash = "a17bcc9bcbe0685dd485f9fb15764e85b5888619";
			break;

		case "b1.5":
			type = ServerType.BETA;
			serverVersion = "b1.5";
			hash = "5bef896515c1d6fbbdef9976b95c6a23534e4eb1";
			break;

		case "b1.4_01":
			type = ServerType.BETA;
			serverVersion = "b1.4_01";
			hash = "0b34fedf13b96829d256d722ebb966c55f893b45";
			break;

		case "b1.4":
			type = ServerType.BETA;
			serverVersion = "b1.4";
			hash = "b6e923a3438711a9a9c438ee6eb996a6f37950ef";
			break;

		case "b1.3_01": //The server didn't seem to get a hot patch despite the client doing so
		case "b1.3b":
			type = ServerType.BETA;
			serverVersion = "b1.3"; //b1.3-221731
			hash = "5cc2aa79b477761cd229d1c3075a6a3afb2bc3ce";
			break;

		case "b1.2_02": //The server didn't seem to get a second hot patch despite the client doing so
		case "b1.2_01":
			type = ServerType.BETA;
			serverVersion = "b1.2_01";
			hash = "4f2a8d6035dd8bde767b91613cd9ea03c65b465a";
			break;

		case "b1.2":
			type = ServerType.BETA;
			serverVersion = "b1.2";
			hash = "2385685c0f1369eb2069858681ee6725ea4a4e7e";
			break;

		case "b1.1_02":
			type = ServerType.BETA;
			serverVersion = "b1.1_02";
			hash = "cc1bc7e0f46541053d5111e0dddd5c58286f2d5e";
			break;

		case "b1.1_01":
			type = ServerType.BETA;
			serverVersion = "b1.1_01";
			hash = "7f320f1b89795924ab1a82234b5b3db8106a2595";
			break;

		case "b1.0.2": //The server didn't seem to get a version bump despite the client doing so
		case "b1.0_01":
			type = ServerType.BETA;
			serverVersion = "b1.0-2"; //Sometimes listed as b1.0_01
			hash = "5270b250d8a1c48f170d53b4b3874b01c55ec72c";
			break;

		case "b1.0":
			type = ServerType.BETA;
			serverVersion = "b1.0-1";
			hash = "582fa899199e9eb5d739806db4f4f06655edff3a";
			break;

		case "a1.2.6":
			type = ServerType.ALPHA;
			serverVersion = "a0.2.8";
			hash = "4d1a4c2f1513b1c019b8ab1b720b8881caccd0d4";
			break;

		case "a1.2.5":
			type = ServerType.ALPHA;
			serverVersion = "a0.2.7";
			hash = "f4d5a32b582b6f4d43a7d9c06297798be809f034";
			break;

		case "a1.2.4_01":
			type = ServerType.ALPHA;
			serverVersion = "a0.2.6_02";
			hash = "2939d4998fab5dcef33c27046ee34c53db151bc2";
			break;

		case "a1.2.3_05":
			type = ServerType.ALPHA;
			serverVersion = "a0.2.6";
			hash = "7f69b0f797ad8ced39f0b84af0ed3412dad9233d";
			break;

		case "a1.2.3_04":
			type = ServerType.ALPHA;
			serverVersion = "a0.2.5_02";
			hash = "6432a0da1ad8ebd0da593ee7a11bc34056d0a1af";
			break;

		case "a1.2.3_02":
			type = ServerType.ALPHA;
			serverVersion = "a0.2.5_01";
			hash = "5ce1d04d71a573ad0c87578a734879b4ea5d6682";
			break;

		case "a1.2.3_01":
			type = ServerType.ALPHA;
			serverVersion = "a0.2.5-2";
			hash = "f75f093fc2fb3ae649d41d1cab92bb86b199a3f6";
			break;

		case "a1.2.3":
			type = ServerType.ALPHA;
			serverVersion = "a0.2.5-1";
			hash = "bfeffb8caa85bcf23f6bfeb9896c6b9e05dc3b96";
			break;

		case "a1.2.2b": //The server didn't seem to get a hot patch despite the client doing so
		case "a1.2.2a":
			type = ServerType.ALPHA;
			serverVersion = "a0.2.4";
			hash = "bc5629edf607f166b8e86a5bfbde4dbaef31353d";
			break;

		case "a1.2.1_01": //The server didn't seem to get a hot patch despite the client doing so
		case "a1.2.1":
			type = ServerType.ALPHA;
			serverVersion = "a0.2.3";
			hash = "2bb3c32eb14f9fb6f98984de04cf9bdbc827d496";
			break;

		case "a1.2.0_02": //The server didn't seem to get a hot patch despite the client doing so
		case "a1.2.0_01":
			type = ServerType.ALPHA;
			serverVersion = "a0.2.2_01";
			hash = "729c2798c2781a6f8109d8dfe21cf0cc4ae2d16c";
			break;

		case "a1.2.0":
			type = ServerType.ALPHA;
			serverVersion = "a0.2.2";
			hash = "ca5010656c57b43e13e0dda7226540201be77773";
			break;

		case "a1.1.2_01": //The server didn't seem to get a hot patch despite the client doing so
		case "a1.1.2":
			type = ServerType.ALPHA;
			serverVersion = "a0.2.1";
			hash = "d409a34862ea76a65bde850cc450597cbf9b0b31";
			break;

		case "a1.1.0":
			type = ServerType.ALPHA;
			serverVersion = "a0.2.0_01";
			hash = "7a1482f3e3f4c67f6e245429189c024877008c5a";
			break;

		case "a1.0.17_04":
		case "a1.0.17_02":
			type = ServerType.ALPHA;
			serverVersion = "a0.1.4";
			hash = "140f5e242d156392a3580544282c422f708c3442";
			break;

		case "a1.0.16":
			type = ServerType.ALPHA;
			serverVersion = "a0.1.2_01";
			hash = "f4c4abddd0ed5f9cdcb8621e3981c98bd5f6031e";
			break;

		case "a1.0.15":
			type = ServerType.ALPHA;
			serverVersion = "a0.1.0";
			hash = "5af0acc21962ca0710d2f82923f3f988d728c504";
			break;

		case "a1.0.14":
		case "a1.0.11":
		case "a1.0.5_01":
		case "a1.0.4":
		case "c0.30_01c":
		case "c0.0.13a":
		case "c0.0.13a_03":
		case "c0.0.11a":
		case "inf-20100618":
		case "rd-161348":
		case "rd-160052":
		case "rd-20090515":
		case "rd-132328":
		case "rd-132211":
			if (mergeOrder != JarMergeOrder.CLIENT_ONLY) {
				throw new IllegalArgumentException("Can only use Minecraft version " + version.id + " with client merge only");
			}
			return;

		default:
			throw new IllegalArgumentException("Unexpected Minecraft version " + version.id + " without server jar");
		}

		Downloads server = version.new Downloads();
		server.url = type.getDownloadURL(serverVersion);
		server.sha1 = hash;
		version.downloads.put("server", server);
	}

	static String intermediaries(String version) {
		switch (version) {
		case "1.2.5":
			return "https://gist.githubusercontent.com/Chocohead/b7ea04058776495a93ed2d13f34d697a/raw/1.2.5 Merge.tiny".replace(" ", "%20");

		case "b1.7.3":
			return "https://gist.githubusercontent.com/Chocohead/b7ea04058776495a93ed2d13f34d697a/raw/Beta 1.7.3 Merge.tiny".replace(" ", "%20");

		case "1.8.9":
			return "https://github.com/Legacy-Fabric/Legacy-Intermediaries/raw/master/mappings/1.8.9.tiny";
		
		default:
			return "https://github.com/FabricMC/intermediary/raw/master/mappings/" + UrlEscapers.urlPathSegmentEscaper().escape(version) + ".tiny";
		}
	}
}
