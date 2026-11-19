package app.cairn.browser.data

/**
 * Navigational intent: typing a site's name ("youtube", "insta", "you tube", "open flipkart") opens the site directly
 * instead of a results page. Covers the most-visited sites worldwide and in India, plus anything in the user's history.
 */
object Sites {
    private val map: Map<String, String> by lazy {
        val m = HashMap<String, String>()
        fun add(domain: String, vararg names: String) { names.forEach { m[norm(it)] = domain }; m[norm(domain.substringBefore('.'))] = m[norm(domain.substringBefore('.'))] ?: domain }
        add("youtube.com", "youtube", "you tube", "yt", "utube", "youtub", "yutube")
        add("music.youtube.com", "youtube music", "yt music")
        add("instagram.com", "instagram", "insta", "ig", "instgram")
        add("facebook.com", "facebook", "fb")
        add("whatsapp.com", "whatsapp"); add("web.whatsapp.com", "whatsapp web", "wa web")
        add("x.com", "x", "twitter", "twiter")
        add("reddit.com", "reddit"); add("linkedin.com", "linkedin", "linked in")
        add("wikipedia.org", "wikipedia", "wiki"); add("github.com", "github", "git hub"); add("gitlab.com", "gitlab")
        add("stackoverflow.com", "stackoverflow", "stack overflow"); add("chatgpt.com", "chatgpt", "chat gpt", "openai chat")
        add("claude.ai", "claude"); add("perplexity.ai", "perplexity"); add("gemini.google.com", "gemini")
        add("amazon.in", "amazon", "amazon india", "amzn"); add("amazon.com", "amazon us", "amazon.com")
        add("flipkart.com", "flipkart", "flipkat", "flip kart"); add("myntra.com", "myntra"); add("ajio.com", "ajio")
        add("meesho.com", "meesho"); add("nykaa.com", "nykaa"); add("snapdeal.com", "snapdeal"); add("tatacliq.com", "tata cliq", "tatacliq")
        add("croma.com", "croma"); add("reliancedigital.in", "reliance digital"); add("vijaysales.com", "vijay sales")
        add("jiomart.com", "jiomart", "jio mart"); add("bigbasket.com", "bigbasket", "big basket"); add("blinkit.com", "blinkit")
        add("zeptonow.com", "zepto"); add("swiggy.com", "swiggy"); add("zomato.com", "zomato"); add("dunzo.com", "dunzo")
        add("ebay.com", "ebay"); add("walmart.com", "walmart"); add("target.com", "target"); add("bestbuy.com", "best buy", "bestbuy")
        add("aliexpress.com", "aliexpress", "ali express"); add("etsy.com", "etsy"); add("ikea.com", "ikea"); add("apple.com", "apple")
        add("samsung.com", "samsung"); add("oneplus.in", "oneplus", "one plus"); add("mi.com", "xiaomi", "mi store")
        add("netflix.com", "netflix"); add("primevideo.com", "prime video", "amazon prime video"); add("hotstar.com", "hotstar", "disney hotstar", "jiohotstar")
        add("jiocinema.com", "jiocinema", "jio cinema"); add("sonyliv.com", "sonyliv", "sony liv"); add("zee5.com", "zee5")
        add("spotify.com", "spotify"); add("open.spotify.com", "spotify web"); add("gaana.com", "gaana"); add("jiosaavn.com", "jiosaavn", "saavn")
        add("twitch.tv", "twitch"); add("primevideo.com", "primevideo"); add("disneyplus.com", "disney plus", "disney+")
        add("gmail.com", "gmail"); add("outlook.live.com", "outlook", "hotmail"); add("mail.yahoo.com", "yahoo mail"); add("proton.me", "proton", "protonmail")
        add("drive.google.com", "google drive", "drive"); add("docs.google.com", "google docs"); add("maps.google.com", "google maps")
        add("translate.google.com", "google translate"); add("google.com", "google"); add("bing.com", "bing"); add("duckduckgo.com", "duckduckgo", "ddg")
        add("yahoo.com", "yahoo"); add("dropbox.com", "dropbox"); add("notion.so", "notion"); add("figma.com", "figma"); add("canva.com", "canva")
        add("zoom.us", "zoom"); add("slack.com", "slack"); add("discord.com", "discord"); add("telegram.org", "telegram"); add("web.telegram.org", "telegram web")
        add("pinterest.com", "pinterest"); add("quora.com", "quora"); add("medium.com", "medium"); add("tumblr.com", "tumblr"); add("threads.net", "threads")
        add("snapchat.com", "snapchat", "snap"); add("tiktok.com", "tiktok", "tik tok"); add("sharechat.com", "sharechat"); add("moj.app", "moj")
        add("paytm.com", "paytm"); add("phonepe.com", "phonepe", "phone pe"); add("pay.google.com", "gpay", "google pay"); add("paypal.com", "paypal")
        add("hdfcbank.com", "hdfc", "hdfc bank"); add("onlinesbi.sbi", "sbi", "sbi online", "state bank"); add("icicibank.com", "icici", "icici bank")
        add("axisbank.com", "axis bank", "axis"); add("kotak.com", "kotak", "kotak bank"); add("zerodha.com", "zerodha"); add("kite.zerodha.com", "kite")
        add("groww.in", "groww"); add("upstox.com", "upstox"); add("angelone.in", "angel one"); add("moneycontrol.com", "moneycontrol", "money control")
        add("nseindia.com", "nse"); add("bseindia.com", "bse"); add("tradingview.com", "tradingview"); add("coinbase.com", "coinbase"); add("binance.com", "binance")
        add("irctc.co.in", "irctc"); add("makemytrip.com", "makemytrip", "mmt", "make my trip"); add("goibibo.com", "goibibo"); add("cleartrip.com", "cleartrip")
        add("ixigo.com", "ixigo"); add("yatra.com", "yatra"); add("booking.com", "booking", "booking.com"); add("airbnb.com", "airbnb"); add("agoda.com", "agoda")
        add("oyorooms.com", "oyo"); add("redbus.in", "redbus", "red bus"); add("uber.com", "uber"); add("olacabs.com", "ola"); add("rapido.bike", "rapido")
        add("expedia.com", "expedia"); add("tripadvisor.com", "tripadvisor"); add("skyscanner.net", "skyscanner"); add("goindigo.in", "indigo"); add("airindia.com", "air india")
        add("bookmyshow.com", "bookmyshow", "bms", "book my show"); add("district.in", "district"); add("practo.com", "practo"); add("1mg.com", "1mg", "tata 1mg")
        add("pharmeasy.in", "pharmeasy"); add("apollo247.com", "apollo"); add("urbancompany.com", "urban company"); add("justdial.com", "justdial", "just dial")
        add("naukri.com", "naukri"); add("indeed.com", "indeed"); add("glassdoor.com", "glassdoor"); add("internshala.com", "internshala"); add("unstop.com", "unstop")
        add("timesofindia.indiatimes.com", "times of india", "toi"); add("ndtv.com", "ndtv"); add("hindustantimes.com", "hindustan times", "ht")
        add("indianexpress.com", "indian express"); add("thehindu.com", "the hindu"); add("aajtak.in", "aaj tak", "aajtak"); add("bhaskar.com", "dainik bhaskar", "bhaskar")
        add("amarujala.com", "amar ujala"); add("jagran.com", "jagran", "dainik jagran"); add("news18.com", "news18"); add("indiatoday.in", "india today")
        add("livemint.com", "mint", "livemint"); add("economictimes.indiatimes.com", "economic times", "et"); add("bbc.com", "bbc"); add("cnn.com", "cnn")
        add("nytimes.com", "nytimes", "new york times", "nyt"); add("theguardian.com", "guardian"); add("reuters.com", "reuters"); add("bloomberg.com", "bloomberg")
        add("espncricinfo.com", "cricinfo", "espncricinfo"); add("cricbuzz.com", "cricbuzz"); add("espn.com", "espn"); add("iplt20.com", "ipl")
        add("chess.com", "chess"); add("lichess.org", "lichess"); add("leetcode.com", "leetcode"); add("codeforces.com", "codeforces"); add("geeksforgeeks.org", "gfg", "geeksforgeeks")
        add("w3schools.com", "w3schools"); add("developer.mozilla.org", "mdn"); add("coursera.org", "coursera"); add("udemy.com", "udemy"); add("khanacademy.org", "khan academy")
        add("byjus.com", "byjus"); add("unacademy.com", "unacademy"); add("pw.live", "physics wallah", "pw"); add("duolingo.com", "duolingo")
        add("imdb.com", "imdb"); add("rottentomatoes.com", "rotten tomatoes"); add("goodreads.com", "goodreads"); add("archive.org", "internet archive", "archive")
        add("weather.com", "weather"); add("speedtest.net", "speedtest", "speed test"); add("fast.com", "fast"); add("translate.yandex.com", "yandex translate")
        add("cred.club", "cred"); add("swiggy.com/instamart", "instamart"); add("lenskart.com", "lenskart"); add("pepperfry.com", "pepperfry"); add("decathlon.in", "decathlon")
        add("boat-lifestyle.com", "boat"); add("firstcry.com", "firstcry"); add("shopsy.in", "shopsy"); add("olx.in", "olx"); add("cars24.com", "cars24"); add("cardekho.com", "cardekho")
        add("99acres.com", "99acres"); add("magicbricks.com", "magicbricks"); add("housing.com", "housing"); add("nobroker.in", "nobroker")
        add("uidai.gov.in", "aadhaar", "uidai"); add("incometax.gov.in", "income tax"); add("digilocker.gov.in", "digilocker"); add("passportindia.gov.in", "passport seva", "passport")
        add("epfindia.gov.in", "epfo", "pf"); add("india.gov.in", "india gov"); add("parivahan.gov.in", "parivahan"); add("cowin.gov.in", "cowin")
        AMBIGUOUS.forEach { m.remove(it) }
        m
    }

    /** Everyday words that are more often searches than site names: never auto-open a site for these. */
    private val AMBIGUOUS = setOf("weather", "fast", "drive", "archive", "district", "housing", "boat", "passport", "pf", "et", "ht", "pw", "snap", "medium", "target",
            "threads", "kite", "mint", "axis", "mi", "proton", "india", "notion", "zoom", "chess", "translate", "maps", "docs", "pay", "web", "music", "open", "mail",
            "booking", "practo1", "apollo", "indigo", "speedtest1", "news18x", "moj", "cred", "pharmeasy1",
        "news", "games", "jobs", "movies", "cricket", "recipes", "hotels", "flights", "shopping", "time", "date", "calculator", "dictionary", "images", "videos", "email")

    private fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9+]"), "")

    private val VERBS = Regex("^(open|go to|goto|visit|launch|kholo|khol|chalao|show)\\s+|\\s+(kholo|khol do|khol de|open karo|open kr|open kar|chalao|par jao|pe jao|website|site|\\.com)$", RegexOption.IGNORE_CASE)

    /** Returns https://… for a navigational query, or null if it's a real search. */
    fun resolve(input: String): String? {
        var t = input.trim().lowercase()
        if (t.isEmpty() || t.length > 40) return null
        repeat(2) { t = t.replace(VERBS, "").trim() }
        if (t.split(Regex("\\s+")).size > 3) return null
        map[norm(t)]?.let { return "https://$it" }
        // A site the user has visited before: "zomato" → zomato.com
        val n = norm(t); if (n.length < 3) return null
        return runCatching { BrowserDb.topHosts(400) }.getOrDefault(emptyList()).firstOrNull { h ->
            if (h == CairnSearch.HOST) return@firstOrNull false
            val label = h.removePrefix("www.").removePrefix("m.").substringBefore('.')
            norm(label) == n
        }?.let { "https://$it" }
    }

    /** Registrable name of a host: "en.m.wikipedia.org" → "wikipedia". */
    fun label(host: String): String {
        val p = host.lowercase().removePrefix("www.").split('.').filter { it.isNotEmpty() }
        if (p.size < 2) return host
        return if (p.size >= 3 && p[p.size - 2] in setOf("co", "com", "org", "net", "gov", "ac", "edu")) p[p.size - 3] else p[p.size - 2]
    }
    fun isNavigational(query: String, host: String) = norm(query).let { it.length >= 3 && it !in AMBIGUOUS && it == norm(label(host)) }
}
