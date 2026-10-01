package com.tivizone.player.data

import android.content.Context
import java.util.regex.Pattern

object MultiStreamManager {

    val MAIN_CATEGORIES = listOf(
        Category("MAIN_FREETV", "1. FreeTV"),
        Category("MAIN_RUSSIAN", "2. Russian"),
        Category("MAIN_SPORT", "3. Sport"),
        Category("MAIN_SKY", "4. Sky"),
        Category("MAIN_KIDS", "5. Kids"),
        Category("MAIN_MUSIK", "6. Musik"),
        Category("MAIN_LIVE_EVENTS", "7. Live Events"),
        Category("MAIN_DOKU", "8. Doku"),
        Category("MAIN_247", "9. 24/7 Filme&Serien"),
        Category("MAIN_PRIVAT", "10. Privat")
    )

    private val BLACKLIST_CATEGORY_IDS = setOf(
        "570",   // AT| DAZN PPV
        "1760",  // DE| SPOTIFY INF & ᴿᴬᵂ
        "17",    // CH| SWITZERLAND HD/4K
        "1861",  // CH| MYSPORTS ᴿᴬᵂ
        "1714",  // CH| BLUE SPORT ᴿᴬᵂ
        "1862",  // CH| BLUE SPORT DIRECT ᴿᴬᵂ
        "1962",  // CH| SFL PPV
        "972",   // CH| DAZN PPV
        "1631",  // AT| CANAL+ ONLINE UNTERHALTUNG ᴿᴬᵂ
        "1630",  // AT| CANAL+ ONLINE SPORT ᴿᴬᵂ
        "2033",  // AT| JOYN ᴿᴬᵂ
        "1955",  // AT| AUSTRIA ⱽᴵᴾ
        "54"     // AT| AUSTRIA HD/4K
    )
    val MAIN_CATEGORY_RAW_IDS = mapOf(
        "MAIN_FREETV" to listOf("1339", "1357", "186", "1353", "509"),
        "MAIN_RUSSIAN" to listOf("6"),
        "MAIN_SPORT" to listOf("1267", "667", "1633", "333", "1762", "1761", "1137", "1115", "509"),
        "MAIN_SKY" to listOf("1268", "668", "513", "509", "186"),
        "MAIN_KIDS" to listOf("334", "509", "186"),
        "MAIN_MUSIK" to listOf("186", "509", "305"),
        "MAIN_LIVE_EVENTS" to listOf("2018", "512", "2231", "2262", "1215", "901", "1672", "433", "543", "461", "980", "1431"),
        "MAIN_DOKU" to listOf("335", "186", "509"),
        "MAIN_247" to listOf("2211", "2210", "2209", "1241", "1255", "545", "500", "991", "1049", "1063", "332", "1756", "1757", "1758", "1759"),
        "MAIN_PRIVAT" to listOf("16")
    )

    fun getRawCategoryIdsForMain(mainCatId: String, rawCategories: List<Category> = emptyList()): List<String> {
        val staticIds = MAIN_CATEGORY_RAW_IDS[mainCatId] ?: emptyList()
        if (rawCategories.isEmpty()) return staticIds
        val dynamicIds = rawCategories.filter { cat ->
            if (BLACKLIST_CATEGORY_IDS.contains(cat.id)) return@filter false
            val u = cat.name.uppercase()
            if (!u.startsWith("DE|") && !u.startsWith("DE:") && !u.startsWith("DE ") &&
                !u.startsWith("RU|") && !u.startsWith("RU:") && !u.startsWith("RU ") &&
                !u.startsWith("PRIME") && !u.startsWith("JOYN") && !u.startsWith("WOW") &&
                !u.contains("FOR ADULTS") && !u.contains("ADULT")) {
                return@filter false
            }
            getSubcatDefaultMain(cat.name) == mainCatId
        }.map { it.id }
        return (staticIds + dynamicIds).distinct()
    }

    private val DOKU_CHANNELS = mapOf(
        "ZDFINFO" to "ZDFinfo",
        "KABELEINSDOKU" to "Kabel Eins Doku",
        "N24DOKU" to "N24 Doku",
        "WELTDERWUNDER" to "Welt der Wunder",
        "FOCUSTVREPORTAGE" to "Focus TV Reportage",
        "DISCOVERY" to "Discovery Channel",
        "DISCOVERYCHANNEL" to "Discovery Channel",
        "NATIONALGEOGRAPHIC" to "National Geographic",
        "NATGEOWILD" to "Nat Geo Wild",
        "ANIMALPLANET" to "Animal Planet",
        "HISTORY" to "History Channel",
        "HISTORYPLAY" to "History Channel",
        "SKYDOCUMENTARIES" to "Sky Documentaries",
        "SKYNATURE" to "Sky Nature",
        "SKYCRIME" to "Sky Crime",
        "GEOTELEVISION" to "Geo Television",
        "GEO" to "Geo Television",
        "SPIEGELGESCHICHTE" to "Spiegel Geschichte",
        "SPIEGELTVGESCHICHTE" to "Spiegel Geschichte",
        "SPIEGELTVKONFLIKTE" to "Spiegel TV Konflikte",
        "CURIOSITYCHANNELPOWEREDBYSPIEGEL" to "Curiosity Channel",
        "CURIOSITYNOW" to "Curiosity Now",
        "MARCOPOLOTV" to "Marco Polo TV",
        "TRAVELXP" to "Travelxp 4K",
        "TRAVELXP4K" to "Travelxp 4K",
        "BERGBLICK" to "Bergblick",
        "TERRAMATERWILD" to "Terra Mater Wild",
        "BBCHISTORY" to "BBC History",
        "BBCTRAVEL" to "BBC Travel",
        "MAGELLANTVNOW" to "MagellanTV Now",
        "ONETERRA" to "One Terra",
        "XPLORE" to "Xplore",
        "LOVETHEPLANET" to "Love the Planet",
        "INSIGHT" to "Insight TV",
        "INSIGHTTV" to "Insight TV",
        "WEDOTVBIGSTORIES" to "wedotv Big Stories",
        "WAIDWERK" to "Waidwerk",
        "CRIME+INVESTIGATION" to "Crime & Investigation",
        "CRIME+INVESTIGATIONPLAY" to "Crime & Investigation",
        "CRIMEINVESTIGATION" to "Crime & Investigation",
        "CRIMEINVESTIGATIONPLAY" to "Crime & Investigation",
        "CRIMESCENETV" to "Crime Scene TV",
        "TOPTRUECRIME" to "Top True Crime",
        "AMERICANCRIMES" to "American Crimes",
        "FILMRISETRUECRIME" to "FilmRise True Crime",
        "TAETERJAGDCRIMESCENESOLVERS" to "Täterjagd (Crime Scene Solvers)",
        "TTERJAGDCRIMESCENESOLVERS" to "Täterjagd (Crime Scene Solvers)"
    )

    private val SPECIAL_REROUTES = mapOf(
        "SYFY" to "MAIN_SKY",
        "TLC" to "MAIN_FREETV"
    )

    private val FREETV_ROUTING_KEYS = setOf(
        "PROSIEBEN", "PROSIEBENMAXX", "RTL", "RTLZWEI", "SAT1GOLD", "SAT1", "VOX",
        "KABELEINS", "SIXX", "DMAX", "COMEDYCENTRAL", "EENTERTAINM", "EENTERTAINMENT",
        "NTV", "NITRO", "WELT", "SUPERRTL", "RTLSUPER", "TELE5", "TLC", "ATV"
    )

    private val KIDS_ROUTING_KEYS = setOf("KIKA", "DISNEYCHANNEL")

    private val SPORT_ROUTING_KEYS = mutableSetOf(
        "SPORT1", "EUROSPORT1", "EUROSPORT2", "DAZNBAR1", "DAZNBAR2", "SKYSPORTNEWS",
        "SKYSPORTPREMIERLEAGUE", "SKYSPORTTOPEVENT", "SKYSPORTBUNDESLIGA", "SKYSPORTF1",
        "SKYSPORTGOLF", "SKYSPORTMIX", "SKYSPORTTENNIS", "SKYSPORTAUSTRIA1",
        "SKYSPORTAUSTRIA2", "SKYSPORTAUSTRIA3", "SKYSPORTAUSTRIA4"
    ).apply {
        for (i in 1..10) {
            add("SKYSPORTBUNDESLIGA$i")
            add("SKYSPORT$i")
        }
    }

    private val GERMAN_MUSIC_KEYS = setOf(
        "DELUXEMUSIC", "MTV", "JUKEBOX", "GOLDSTARTV", "GUTELAUNE", "GUTELAUNETV", "STINGRAYCLASSICA"
    )

    fun cleanChannelName(name: String, isRussian: Boolean): String {
        val orig = name.trim()
        if (orig.startsWith("#") && orig.endsWith("#")) {
            return "--- [TRENNER] ---"
        }
        if (isRussian) {
            return orig
        }

        var cleaned = orig
        val origUpper = orig.uppercase()
        val isUhd = origUpper.contains("UHD") || orig.contains("ᵁᴴᴰ")

        // Eigenständige UHD Event- und Sondersender (nicht mit Standard-HD bündeln!)
        if (isUhd) {
            if (origUpper.contains("RTL") && !origUpper.contains("RTL 2") && !origUpper.contains("RTL ZWEI") &&
                !origUpper.contains("NITRO") && !origUpper.contains("LIVING") && !origUpper.contains("PASSION") &&
                !origUpper.contains("CRIME") && !origUpper.contains("SUPER") && !origUpper.contains("UP")) {
                return "RTL UHD"
            }
            if (origUpper.contains("PROSIEBENSAT") || origUpper.contains("PROSIEBEN SAT")) {
                return "PROSIEBENSAT.1 UHD"
            }
            if (origUpper.contains("UHD1") || origUpper.contains("UHD 1")) {
                return "UHD1"
            }
            if (origUpper.contains("QVC ZWEI") || origUpper.contains("QVC 2")) {
                return "QVC ZWEI UHD"
            }
            if (origUpper.contains("QVC")) {
                return "QVC UHD"
            }
        }

        val prefixPattern = Pattern.compile("^(?:DE|PRIME|JOYN|WOW|SKYGO|SKY\\s*GO|RU|ADULT|AT|CH|UK)\\s*[:|\\-]\\s*", Pattern.CASE_INSENSITIVE)
        for (i in 0..2) {
            cleaned = prefixPattern.matcher(cleaned).replaceAll("")
        }

        for (p in listOf("DE ", "PRIME ", "WOW ", "JOYN ", "SKYGO ", "SKY GO ", "RU ", "ADULT ", "UK ")) {
            if (cleaned.uppercase().startsWith(p)) {
                cleaned = cleaned.substring(p.length).trim()
            }
        }

        cleaned = cleaned.replace(Regex("\\s*[|\\-]\\s*(?:DE|UK)\\b", RegexOption.IGNORE_CASE), "")
        cleaned = cleaned.replace(Regex("\\s*\\(\\s*(?:MOBIL|MOBILE|LOW\\s*BIT|LOWBIT|LOW|720[Pp]|1080[Pp]|3840[Pp]|SAT|KABEL|WEB\\s*1080|WEB\\s*720[Pp]|WEB)\\s*\\)", RegexOption.IGNORE_CASE), "")
        cleaned = cleaned.replace(Regex("\\b(?:4K|UHD|FHD|HD|SD|RAW|HEVC|60FPS|50FPS|720[Pp]|1080[Pp]|3840[Pp])\\b", RegexOption.IGNORE_CASE), "")
        cleaned = cleaned.replace(Regex("[ᴴᴰ⁴ᴷᶠʰᵈˢᵈᴿᴬᵂʰᵉᵛᶜᵁᴴᴰ³⁸⁴⁰ᴾ¹⁰⁸⁰ᴾ⁷²⁰ᴾ⁶⁰ᶠᵖˢ⁵⁰ᶠᵖˢ◉]"), "")
        cleaned = cleaned.replace(Regex("\\s+"), " ").trim(' ', '-', '|', ':')

        val upper = cleaned.uppercase()
        return when {
            upper in listOf("KABEL 1", "KABEL EINS") -> "KABEL EINS"
            upper in listOf("KABEL 1 CLASSICS", "KABEL EINS CLASSICS") -> "KABEL EINS CLASSICS"
            upper in listOf("KABEL 1 DOKU", "KABEL EINS DOKU") -> "KABEL EINS DOKU"
            upper in listOf("RTL 2", "RTL ZWEI") -> "RTL ZWEI"
            upper in listOf("RTL NITRO", "NITRO", "NTRO") -> "NITRO"
            upper in listOf("SUPER RTL", "RTL SUPER", "TOGGO RTL SUPER") -> "RTL SUPER"
            upper in listOf("N-TV", "NTV") -> "N-TV"
            upper in listOf("VOX UP", "VOXUP") -> "VOX UP"
            upper in listOf("ZDF INFO", "ZDFINFO") -> "ZDFINFO"
            upper in listOf("ZDF NEO", "ZDFNEO") -> "ZDFNEO"
            upper in listOf("E! ENTERTAINM", "E ENTERTAINM", "E! ENTERTAINMENT") -> "E! ENTERTAINMENT"
            upper in listOf("GUTE LAUNE", "GUTE LAUNE TV") -> "GUTE LAUNE TV"
            else -> cleaned
        }
    }

    fun normalizeKey(name: String): String {
        return name.uppercase().replace(Regex("[^A-Z0-9+]"), "").trim()
    }

    fun evaluateSource(origName: String, subcatName: String): Pair<Int, String> {
        val origU = origName.uppercase()
        val subU = subcatName.uppercase()

        if (origU.contains("LOW BIT") || origU.contains("LOWBIT") || origU.contains("(LOW")) return Pair(20, origName)
        if (origU.contains("MOBIL") || origU.contains("MOBILE")) return Pair(30, origName)
        if (Regex("\\bSD\\b").containsMatchIn(origU) || origName.contains("ˢᵈ")) return Pair(40, origName)
        if (origU.contains("720P") || origU.contains("(720") || origName.contains("⁷²⁰ᴾ")) return Pair(50, origName)
        if (origU.contains("WEB 720")) return Pair(55, origName)

        // 4K / UHD
        if (origU.contains("4K") || origName.contains("⁴ᴷ") || origU.contains("UHD") || origName.contains("ᵁᴴᴰ") || origU.contains("3840")) {
            if (origU.contains("SKYGO") || origU.contains("SKY GO") || subU.contains("SKYGO")) return Pair(92, origName)
            return Pair(99, origName)
        }

        // DVB Satellit (Standard 1080p HD) - Höchste Priorität für FreeTV (Sat.1, RTL, ProSieben etc.)
        val isSat = origU.contains("(SAT)") || origU.contains("DVB-S") || (
            (subU.contains("GENERAL HD") || subU.contains("DEUTSCHLAND HD") || subU.contains("GERMANY HD") || origU.startsWith("DE:") || origU.startsWith("AT:") || origU.startsWith("CH:"))
            && (origU.contains("HD") || origName.contains("ᴴᴰ"))
            && !origU.contains("JOYN") && !origU.contains("RTL+") && !origU.contains("PRIME") && !origU.contains("WOW") && !origU.contains("HEVC") && !origU.contains("WEB") && !origU.contains("KABEL")
            && !subU.contains("JOYN") && !subU.contains("RTL+") && !subU.contains("PRIME") && !subU.contains("WOW") && !subU.contains("HEVC")
        )
        if (isSat) return Pair(95, origName)

        // HEVC H.265 (Category 509 / Sky HEVC)
        if (origU.contains("HEVC") || origName.contains("ʰᵉᵛᶜ") || subU.contains("HEVC")) return Pair(92, origName)

        if (origU.contains("WEB 1080")) return Pair(91, origName)
        if (origU.contains("PRIME") || subU.contains("PRIME")) return Pair(90, origName)
        if (origU.contains("WOW") || subU.contains("WOW")) return Pair(88, origName)
        if (subU.contains("DAZN EXCLUSIVE") || subU.contains("DAZN EXKLUSIV") || origU.contains("DAZN EXCLUSIVE")) return Pair(87, origName)
        if (origU.contains("RTL+") || subU.contains("RTL+")) return Pair(84, origName)
        if (origU.contains("JOYN") || subU.contains("JOYN")) return Pair(82, origName)
        if (origU.contains("(KABEL") || origU.contains("KABEL)") || origU.contains("DVB-C")) return Pair(80, origName)
        if (origU.contains("RAW") || origName.contains("ᴿᴬᵂ")) return Pair(78, origName)
        if (origU.contains("SKYGO") || origU.contains("SKY GO") || subU.contains("SKYGO")) return Pair(76, origName)
        if (origU.contains("HD") || origName.contains("ᴴᴰ")) return Pair(75, origName)
        return Pair(60, origName)
    }

    private fun getSubcatDefaultMain(subcatName: String): String {
        val u = subcatName.uppercase().trim()
        return when {
            u.startsWith("RU|") || u.contains("RUSSIAN") -> "MAIN_RUSSIAN"
            u.contains("FOR ADULTS") || u.contains("ADULT") -> "MAIN_PRIVAT"
            u.contains("24/7") -> "MAIN_247"
            u.contains("PPV") || u.contains("LEAGUES") || u.contains("SOCCER PPV") || u.contains("EVENT") -> "MAIN_LIVE_EVENTS"
            u.contains("SPORT") || u.contains("BUNDESLIGA") || u.contains("DAZN") -> "MAIN_SPORT"
            u.contains("DOCUMENTARY") || u.contains("DOKU") -> "MAIN_DOKU"
            u.contains("MUSIC") || u.contains("MUSIK") -> "MAIN_MUSIK"
            u.contains("KIDS") || u.contains("KINDER") -> "MAIN_KIDS"
            u.contains("SKY") || u.contains("CINEMA") || u.contains("WOW ENTERTAINMENT") -> "MAIN_SKY"
            else -> "MAIN_FREETV"
        }
    }

    fun resolveMainCategoryIdForStream(stream: LiveStream, rawCategories: List<Category>): String {
        val cid = stream.categoryId ?: ""
        val catMap = rawCategories.associateBy { it.id }
        val subcatName = catMap[cid]?.name ?: ""
        val defaultMain = getSubcatDefaultMain(subcatName)
        if (defaultMain == "MAIN_RUSSIAN") return "MAIN_RUSSIAN"
        if (defaultMain == "MAIN_LIVE_EVENTS") return "MAIN_LIVE_EVENTS"
        if (defaultMain == "MAIN_247") return "MAIN_247"
        if (defaultMain == "MAIN_PRIVAT") return "MAIN_PRIVAT"

        val origName = stream.name.trim()
        val cleanName = cleanChannelName(origName, false)
        val normK = normalizeKey(cleanName)

        if (DOKU_CHANNELS.containsKey(normK)) return "MAIN_DOKU"
        if (SPECIAL_REROUTES.containsKey(normK)) return SPECIAL_REROUTES[normK]!!

        // Alle Sky- und Cinema-Sender gehören verbindlich in Sky (oder Sport/Doku)
        if ((normK.contains("SKY") || normK.contains("CINEMA") || normK.contains("ATLANTIC")) && !FREETV_ROUTING_KEYS.contains(normK)) {
            return when {
                SPORT_ROUTING_KEYS.contains(normK) || normK.contains("SPORT") -> "MAIN_SPORT"
                DOKU_CHANNELS.containsKey(normK) -> "MAIN_DOKU"
                else -> "MAIN_SKY"
            }
        }

        if (cid == "509") { // Sky HEVC
            return when {
                GERMAN_MUSIC_KEYS.contains(normK) -> "MAIN_MUSIK"
                FREETV_ROUTING_KEYS.contains(normK) -> "MAIN_FREETV"
                KIDS_ROUTING_KEYS.contains(normK) -> "MAIN_KIDS"
                SPORT_ROUTING_KEYS.contains(normK) -> "MAIN_SPORT"
                else -> "MAIN_SKY"
            }
        }
        if (GERMAN_MUSIC_KEYS.contains(normK)) return "MAIN_MUSIK"
        return defaultMain
    }

    fun findMainCategoryByChannelName(channelName: String): String {
        val cleanName = cleanChannelName(channelName, false)
        val normK = normalizeKey(cleanName)
        if (DOKU_CHANNELS.containsKey(normK)) return "MAIN_DOKU"
        if (SPECIAL_REROUTES.containsKey(normK)) return SPECIAL_REROUTES[normK]!!
        if (GERMAN_MUSIC_KEYS.contains(normK)) return "MAIN_MUSIK"
        if (KIDS_ROUTING_KEYS.contains(normK)) return "MAIN_KIDS"
        if (SPORT_ROUTING_KEYS.contains(normK)) return "MAIN_SPORT"
        if (FREETV_ROUTING_KEYS.contains(normK)) return "MAIN_FREETV"

        if ((normK.contains("SKY") || normK.contains("CINEMA") || normK.contains("ATLANTIC")) && !FREETV_ROUTING_KEYS.contains(normK)) {
            return when {
                SPORT_ROUTING_KEYS.contains(normK) || normK.contains("SPORT") -> "MAIN_SPORT"
                DOKU_CHANNELS.containsKey(normK) -> "MAIN_DOKU"
                else -> "MAIN_SKY"
            }
        }

        val u = channelName.uppercase()
        return when {
            u.contains("SKY") || u.contains("CINEMA") || u.contains("ATLANTIC") -> "MAIN_SKY"
            u.contains("DAZN") || u.contains("BUNDESLIGA") || u.contains("SPORT") -> "MAIN_SPORT"
            u.contains("DISNEY") || u.contains("NICK") || u.contains("KIKA") || u.contains("TOGGO") -> "MAIN_KIDS"
            u.contains("MTV") || u.contains("DELUXE") || u.contains("ROCK") || u.contains("SCHLAGER") -> "MAIN_MUSIK"
            u.contains("DISCOVERY") || u.contains("NAT GEO") || u.contains("PLANET") || u.contains("HISTORY") -> "MAIN_DOKU"
            else -> "MAIN_FREETV"
        }
    }

    fun applyPreferredSources(context: Context, channel: MultiStreamChannel): MultiStreamChannel {
        val preferredId = QualityPreferenceManager.getPreferredStreamId(context, channel.cleanName) ?: return channel
        val prefIndex = channel.sources.indexOfFirst { it.streamId == preferredId }
        if (prefIndex == -1) return channel

        val reordered = channel.sources.toMutableList()
        val preferredSource = reordered.removeAt(prefIndex)
        val updatedLabel = if (!preferredSource.label.contains("⭐")) {
            "${preferredSource.label} ⭐"
        } else {
            preferredSource.label
        }
        val updatedSource = preferredSource.copy(label = updatedLabel, score = 200)
        reordered.add(0, updatedSource)
        return channel.copy(sources = reordered)
    }

    fun buildMultiStreamCategories(
        allStreams: List<LiveStream>,
        rawCategories: List<Category>,
        context: Context? = null
    ): Map<String, List<MultiStreamChannel>> {
        val catMap = rawCategories.associateBy { it.id }
        val streamsByMain = mutableMapOf<String, MutableList<Pair<LiveStream, Pair<Int, String>>>>()
        for (m in MAIN_CATEGORIES) {
            streamsByMain[m.id] = mutableListOf()
        }

        for (stream in allStreams) {
            val cid = stream.categoryId ?: continue
            if (BLACKLIST_CATEGORY_IDS.contains(cid)) continue
            val origName = stream.name.trim()
            if (origName.startsWith("#") && origName.endsWith("#")) continue
            if (origName.uppercase().contains("SPOTIFY")) continue

            val subcat = catMap[cid]?.name ?: ""
            val defaultMain = getSubcatDefaultMain(subcat)
            val isRu = (defaultMain == "MAIN_RUSSIAN")
            var cleanName = cleanChannelName(origName, isRu)
            if (cleanName.startsWith("---")) continue

            var normK = normalizeKey(cleanName)
            val eval = evaluateSource(origName, subcat)

            val targetMains = mutableListOf<String>()

            when {
                defaultMain == "MAIN_RUSSIAN" -> {
                    targetMains.add("MAIN_RUSSIAN")
                }
                defaultMain == "MAIN_LIVE_EVENTS" || origName.uppercase().contains("PPV") || subcat.uppercase().contains("PPV") -> {
                    targetMains.add("MAIN_LIVE_EVENTS")
                }
                defaultMain == "MAIN_247" -> {
                    targetMains.add("MAIN_247")
                }
                defaultMain == "MAIN_PRIVAT" -> {
                    targetMains.add("MAIN_PRIVAT")
                }
                DOKU_CHANNELS.containsKey(normK) -> {
                    targetMains.add("MAIN_DOKU")
                    cleanName = DOKU_CHANNELS[normK] ?: cleanName
                }
                SPECIAL_REROUTES.containsKey(normK) -> {
                    targetMains.add(SPECIAL_REROUTES[normK]!!)
                }
                // Sky & Cinema sender IMMER zu Sky (oder Sport/Doku) und NIEMALS zu FreeTV!
                (normK.contains("SKY") || normK.contains("CINEMA") || normK.contains("ATLANTIC")) && !FREETV_ROUTING_KEYS.contains(normK) -> {
                    when {
                        SPORT_ROUTING_KEYS.contains(normK) || normK.contains("SPORT") -> targetMains.add("MAIN_SPORT")
                        DOKU_CHANNELS.containsKey(normK) -> targetMains.add("MAIN_DOKU")
                        else -> targetMains.add("MAIN_SKY")
                    }
                }
                cid == "509" -> { // Sky HEVC
                    when {
                        GERMAN_MUSIC_KEYS.contains(normK) -> {
                            targetMains.add("MAIN_FREETV")
                            targetMains.add("MAIN_MUSIK")
                        }
                        FREETV_ROUTING_KEYS.contains(normK) -> targetMains.add("MAIN_FREETV")
                        KIDS_ROUTING_KEYS.contains(normK) -> targetMains.add("MAIN_KIDS")
                        SPORT_ROUTING_KEYS.contains(normK) -> targetMains.add("MAIN_SPORT")
                        else -> targetMains.add("MAIN_SKY")
                    }
                }
                defaultMain != "MAIN_RUSSIAN" && GERMAN_MUSIC_KEYS.contains(normK) -> {
                    targetMains.add(defaultMain)
                    targetMains.add("MAIN_MUSIK")
                }
                else -> {
                    targetMains.add(defaultMain)
                }
            }

            for (tm in targetMains.distinct()) {
                val cleanedStream = stream.copy(name = cleanName)
                streamsByMain[tm]?.add(Pair(cleanedStream, Pair(eval.first, origName)))
            }
        }

        // Bündelung zu MultiStreamChannels
        val result = mutableMapOf<String, List<MultiStreamChannel>>()

        for ((mainCatId, streamPairs) in streamsByMain) {
            val mainCatName = MAIN_CATEGORIES.firstOrNull { it.id == mainCatId }?.name ?: mainCatId
            val isRussian = (mainCatId == "MAIN_RUSSIAN")

            val groups = streamPairs.groupBy { (s, _) ->
                if (isRussian) s.name else normalizeKey(s.name)
            }

            val channelList = mutableListOf<MultiStreamChannel>()

            for ((_, pairs) in groups) {
                if (pairs.isEmpty()) continue
                val distinctPairs = pairs.distinctBy { it.first.streamId }
                val sortedPairs = distinctPairs.sortedByDescending { it.second.first }
                val bestStream = sortedPairs.first().first
                val cleanName = bestStream.name

                val epgStream = sortedPairs.firstOrNull { !it.first.epgChannelId.isNullOrEmpty() }?.first
                    ?: bestStream

                val sources = sortedPairs.map { (s, eval) ->
                    StreamSource(
                        streamId = s.streamId,
                        name = eval.second,
                        label = eval.second,
                        score = eval.first,
                        subcategory = catMap[s.categoryId]?.name ?: "",
                        epgChannelId = s.epgChannelId
                    )
                }

                val initialChannel = MultiStreamChannel(
                    cleanName = cleanName,
                    originalName = sortedPairs.first().second.second,
                    categoryId = mainCatId,
                    categoryName = mainCatName,
                    icon = bestStream.streamIcon,
                    epgId = epgStream.epgChannelId ?: bestStream.epgChannelId,
                    epgStreamId = epgStream.streamId,
                    sources = sources
                )
                val finalChannel = if (context != null) applyPreferredSources(context, initialChannel) else initialChannel
                channelList.add(finalChannel)
            }

            val sortedChannels = channelList.sortedWith(
                Comparator { c1, c2 ->
                    val p1 = getChannelSortPriority(c1.cleanName, mainCatId)
                    val p2 = getChannelSortPriority(c2.cleanName, mainCatId)
                    if (p1 != p2) {
                        p1.compareTo(p2)
                    } else {
                        NaturalOrderComparator.compare(c1.cleanName, c2.cleanName)
                    }
                }
            )
            result[mainCatId] = sortedChannels
        }

        return result
    }

    private val RU_FEDERAL_PREFIXES = listOf(
        "PERVIYKANAL", "PERVIYHD", "CHANNELONE",
        "ROSSIYA1", "RTRPLANETA", "RTR", "ROSSIYA",
        "MATCHTV", "MATCHHD",
        "HTB", "NTV", "NTVMIR",
        "5KANAL", "PYATITKANAL",
        "RUSSIAK", "KULTURA",
        "ROSSIYA24",
        "THT", "TNT", "THT4", "TNT4",
        "CTC", "STS", "CTSLOVE", "STSLOVE",
        "DOMASHNIY",
        "TV3",
        "PYATNITSA",
        "ZVEZDA",
        "MIR", "MIR24", "MIRHD",
        "PEHTB", "RENTV",
        "CHE",
        "TVCI", "TVC",
        "MOSKVA24", "SAINTPETERBURG", "CURRENTTIME",
        "DOZD", "DOJD", "RTVI", "RTHD", "RTNEWS", "EURONEWS",
        "RBKTV", "RBK", "VREMYA", "365DNEI", "8KANAL", "SARAFAN", "LIFEHD", "UTV", "SUPER", "PRODVIJENIE", "KRIKTV"
    )

    private val RU_MUSIC_KEYWORDS = listOf(
        "MUZ", "RUTV", "TNTMUSIC", "THTMUSIC", "RUSSIANMUSICBOX", "MUSICBOX",
        "EUROPAPLUS", "FIRSTMUSICCHANNEL", "1BY",
        "BRIDGETV", "BRIDGEHD", "BRIDGE", "MTV",
        "LAMINOR", "MUZIKAPERVOGO", "SHANSONTV", "SHANSON", "MCMTOP",
        "DANGETV", "DANCETV", "ICONCERTS", "MEZZO", "MYZENTV"
    )

    private val RU_DOKU_KEYWORDS = listOf(
        "DISCOVERY", "NATIONALGEOGRAPHIC", "NATGEO", "ANIMALPLANET", "ANIMALFAMILY",
        "HISTORY", "H2", "VIASATHISTORY", "VIASATNATURE", "VIASATEXPLORE",
        "RTDOC", "RTG", "NAUKA", "24TECHNO", "DOCTOR",
        "OHOTA", "OKHOTA", "RYBALKA", "OHOTNIK",
        "TRAVEL", "GLAZAMI", "GLAZELLA",
        "ZAGORODNAYA", "ZAGORODNOJA", "ZAGORODNIY", "USADBA", "BOBER",
        "KUCHNIA", "KUHNYA", "EDA", "FOODNETWORK", "TELEKAFE",
        "DAVINCI", "DOCUBOX", "EUREKA", "ZOOPARK", "DOMASHNIEJIVOTNIE",
        "SOVERSHENNOSEKRETNO", "ISTORIYA", "ORUZHIE", "MORSKOI",
        "CBSREALITY", "FINELIVING", "FASHION", "STYLE", "LUXURI",
        "JIVITV", "PSIHOLOGIA", "OBRAZOVATELNIY", "AVTOMOBILNIY", "VOPROSI", "TLC"
    )

    private val RU_KIDS_KEYWORDS = listOf(
        "KARUSEL", "MULT", "MULYTI", "DETSKIY", "DETSKIJ", "STSKIDS", "CTSKIDS",
        "NICKELODEON", "NICKJUNIOR", "CARTOON", "DISNEY", "BOOMERANG",
        "JIMJAM", "GULLI", "2X2", "TIJI", "TLUM",
        "KAPITANFANTASTIKA", "GOSTIAHUSKAZKI", "MAMA", "FIXFOXI"
    )

    private fun getRussianChannelSortPriority(name: String): Int {
        val u = name.uppercase().trim()
        val withoutPrefix = u.replace(Regex("^(?:RU)\\s*[:|\\-]\\s*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*[|\\-]\\s*RU\\b", RegexOption.IGNORE_CASE), "")
        val normK = normalizeKey(withoutPrefix)

        // 1. Standard (Hauptsender)
        val isStd = !normK.contains("MULT") && !normK.contains("KIDS") &&
                !normK.contains("MUSIC") && !normK.contains("SPORT") &&
                !normK.contains("DOC") && !normK.contains("DOCU") &&
                !normK.contains("CINEMA") && !normK.contains("KINO") &&
                !normK.contains("SERIAL") && !normK.contains("FOOTBALL") && !normK.contains("FUTBOL") &&
                RU_FEDERAL_PREFIXES.any { normK.startsWith(it) }

        if (isStd) {
            val idx = RU_FEDERAL_PREFIXES.indexOfFirst { normK.startsWith(it) }
            return 100 + if (idx != -1) idx else 50
        }

        // 2. Musik
        val isMus = !normK.contains("MUZHSKOE") && !normK.contains("MULTIMUZ") &&
                !normK.contains("FOOTBALL") && !normK.contains("FUTBOL") && !normK.contains("HBO") &&
                (RU_MUSIC_KEYWORDS.any { normK.contains(it) } || (withoutPrefix.contains("1 HD") && (withoutPrefix.contains("MUSIC") || withoutPrefix.contains("MUZ") || withoutPrefix == "1 HD")))

        if (isMus) {
            return 200
        }

        // 3. Doku
        val isDok = RU_DOKU_KEYWORDS.any { normK.contains(it) }
        if (isDok) {
            return 300
        }

        // 4. Kinder
        val isKid = withoutPrefix in listOf("O!", "O", "ANI") || RU_KIDS_KEYWORDS.any { normK.contains(it) }
        if (isKid) {
            return 400
        }

        // 5. Rest (Kino, Serien, Sport, etc.)
        return 500
    }

    fun getChannelSortPriority(name: String, catId: String): Int {
        val u = name.uppercase().trim()
        val normK = normalizeKey(u)

        when (catId) {
            "MAIN_FREETV" -> {
                val lcnTop = listOf(
                    "DASERSTE", "ZDF", "RTL", "SAT1", "PROSIEBEN", "VOX", "KABELEINS", "RTLZWEI",
                    "3SAT", "ARTE", "NITRO", "DMAX", "SIXX", "SAT1GOLD", "PROSIEBENMAXX", "VOXUP",
                    "RTLUP", "TELE5", "SERVUSTV", "DF1", "PROSIEBENFUN", "SAT1EMOTIONS",
                    "KABELEINSCLASSICS", "NTV", "WELT", "PHOENIX", "TAGESSCHAU24", "ARDALPHA",
                    "EURONEWS", "ZDFNEO", "ONE", "KIKA", "RTLSUPER", "TOGGOPLUS", "DISNEYCHANNEL", "NICKELODEON",
                    "TLC", "RIC", "EUROSPORT1", "SPORT1", "REDBULLTV", "MTV", "DELUXEMUSIC",
                    "RTLUHD", "PROSIEBENSAT1UHD", "UHD1", "QVCUHD", "QVCZWEIUHD"
                )
                val idx = lcnTop.indexOf(normK)
                if (idx != -1) return idx
                if (u.startsWith("SAT.1 ") || u.startsWith("RTL ")) return 100
                return 200
            }
            "MAIN_RUSSIAN" -> {
                return getRussianChannelSortPriority(name)
            }
            "MAIN_SPORT" -> {
                if (u == "SKY SPORT BUNDESLIGA") return 10
                if (u.contains("SKY SPORT BUNDESLIGA")) return 11
                if (u.contains("BUNDESLIGA")) return 15
                if (u.contains("SKY SPORT TOP EVENT")) return 20
                if (u.contains("SKY SPORT PREMIER LEAGUE")) return 21
                if (u.contains("SKY SPORT F1")) return 22
                if (u.contains("SKY SPORT TENNIS")) return 23
                if (u.contains("SKY SPORT GOLF")) return 24
                if (u.contains("SKY SPORT MIX")) return 25
                if (u.contains("SKY SPORT NEWS")) return 26
                if (Regex("SKY SPORT \\d+").containsMatchIn(u)) return 27
                if (u.contains("DAZN")) return 30
                if (u.contains("SPORTDIGITAL")) return 35
                if (u.contains("SKY SPORT AUSTRIA")) return 40
                if (u.contains("EUROSPORT 1") || u.contains("EUROSPORT 2")) return 45
                if (u.contains("EUROSPORT360") || u.contains("EUROSPORT 360")) return 46
                if (u.contains("EUROSPORT")) return 47
                if (u.contains("SPORT1") || u.contains("SPORT 1")) return 50
                return 100
            }
            "MAIN_SKY" -> {
                if (u.contains("PREMIERE") || u.contains("PREMIEREN")) return 10
                if (u.contains("BLOCKBUSTER") || u.contains("FEELGOOD") || u.contains("SPECIAL") || u.contains("THRILLER")) return 15
                if (u.contains("ACTION") || u.contains("BEST OF") || u.contains("FAMILY")) return 20
                if (u.contains("ATLANTIC") || u.contains("ONE") || u.contains("REPLAY")) return 30
                return 100
            }
            "MAIN_DOKU" -> {
                if (normK in listOf("ZDFINFO", "KABELEINSDOKU", "N24DOKU")) return 10
                if (normK in listOf("DISCOVERYCHANNEL", "DISCOVERY", "NATIONALGEOGRAPHIC", "NATGEOWILD")) return 20
                if (normK in listOf("SKYDOCUMENTARIES", "SKYNATURE", "SKYCRIME")) return 30
                return 100
            }
            "MAIN_MUSIK" -> {
                if (normK in listOf("MTV", "DELUXEMUSIC", "JUKEBOX", "GOLDSTARTV", "GUTELAUNETV")) return 10
                return 100
            }
        }
        return 500
    }

    object NaturalOrderComparator : Comparator<String> {
        private val CHUNK_REGEX = Regex("(\\d+)|(\\D+)")

        override fun compare(s1: String, s2: String): Int {
            val m1 = CHUNK_REGEX.findAll(s1).map { it.value }.toList()
            val m2 = CHUNK_REGEX.findAll(s2).map { it.value }.toList()
            val minLen = minOf(m1.size, m2.size)
            for (i in 0 until minLen) {
                val c1 = m1[i]
                val c2 = m2[i]
                val n1 = c1.toLongOrNull()
                val n2 = c2.toLongOrNull()
                val cmp = if (n1 != null && n2 != null) {
                    n1.compareTo(n2)
                } else {
                    c1.compareTo(c2, ignoreCase = true)
                }
                if (cmp != 0) return cmp
            }
            return m1.size.compareTo(m2.size)
        }
    }
}
