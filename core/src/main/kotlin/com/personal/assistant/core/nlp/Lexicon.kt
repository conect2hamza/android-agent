package com.personal.assistant.core.nlp

import java.time.DayOfWeek

/**
 * Vocabulary for English, Urdu script and Roman Urdu.
 *
 * Roman Urdu has no fixed orthography, so most entries carry several spellings ("shaam", "sham",
 * "shm" is a step too far). Everything here is matched against [TextNormalizer]-folded text, so
 * entries must be lower-case.
 */
object Lexicon {

    // ---------------------------------------------------------------- numbers

    val NUMBER_WORDS: Map<String, Int> = mapOf(
        // English
        "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6,
        "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12,
        "fifteen" to 15, "twenty" to 20, "thirty" to 30, "forty" to 40, "forty-five" to 45,
        "sixty" to 60, "ninety" to 90,
        // Roman Urdu
        "ek" to 1, "aik" to 1, "do" to 2, "teen" to 3, "tean" to 3, "char" to 4, "chaar" to 4,
        "panch" to 5, "paanch" to 5, "chey" to 6, "che" to 6, "chh" to 6, "saat" to 7,
        "aath" to 8, "aat" to 8, "nau" to 9, "no" to 9, "das" to 10, "gyara" to 11,
        "gyarah" to 11, "bara" to 12, "barah" to 12, "pandra" to 15, "pandrah" to 15,
        "bees" to 20, "tees" to 30, "chalees" to 40, "pentalees" to 45, "saath" to 60,
        // Urdu script
        "ایک" to 1, "دو" to 2, "تین" to 3, "چار" to 4, "پانچ" to 5, "چھ" to 6, "چھے" to 6,
        "سات" to 7, "آٹھ" to 8, "نو" to 9, "دس" to 10, "گیارہ" to 11, "بارہ" to 12,
        "پندرہ" to 15, "بیس" to 20, "تیس" to 30, "چالیس" to 40, "ساٹھ" to 60,
    )

    /** "half an hour", "adha ghanta". */
    val HALF_WORDS: Set<String> = setOf("half", "adha", "aadha", "آدھا", "آدھ")

    // ------------------------------------------------------------- day offsets

    /**
     * Words that mean a day relative to today. Urdu "kal" / "کل" and "parson" / "پرسوں" mean both
     * one day *ahead* and one day *behind*; the sign is decided by the intent, so they are stored
     * as a magnitude with [ambiguousDirection] set.
     */
    data class RelativeDay(val offset: Int, val ambiguousDirection: Boolean = false)

    val RELATIVE_DAYS: Map<String, RelativeDay> = mapOf(
        "today" to RelativeDay(0),
        "tonight" to RelativeDay(0),
        "aaj" to RelativeDay(0),
        "aj" to RelativeDay(0),
        "آج" to RelativeDay(0),
        "tomorrow" to RelativeDay(1),
        "tmrw" to RelativeDay(1),
        "yesterday" to RelativeDay(-1),
        "kal" to RelativeDay(1, ambiguousDirection = true),
        "کل" to RelativeDay(1, ambiguousDirection = true),
        "parso" to RelativeDay(2, ambiguousDirection = true),
        "parsun" to RelativeDay(2, ambiguousDirection = true),
        "parson" to RelativeDay(2, ambiguousDirection = true),
        "پرسوں" to RelativeDay(2, ambiguousDirection = true),
    )

    /** Multi-word relative days, checked before the single-word table. */
    val RELATIVE_DAY_PHRASES: Map<String, RelativeDay> = mapOf(
        "day after tomorrow" to RelativeDay(2),
        "the day after tomorrow" to RelativeDay(2),
        "day before yesterday" to RelativeDay(-2),
        "next week" to RelativeDay(7),
        "agle hafte" to RelativeDay(7),
        "agley hafte" to RelativeDay(7),
        "اگلے ہفتے" to RelativeDay(7),
        "last week" to RelativeDay(-7),
        "pichle hafte" to RelativeDay(-7),
        "گزشتہ ہفتے" to RelativeDay(-7),
        "this weekend" to RelativeDay(0),
    )

    // ---------------------------------------------------------------- weekdays

    val WEEKDAYS: Map<String, DayOfWeek> = mapOf(
        "monday" to DayOfWeek.MONDAY, "mon" to DayOfWeek.MONDAY,
        "peer" to DayOfWeek.MONDAY, "pir" to DayOfWeek.MONDAY, "somwar" to DayOfWeek.MONDAY,
        "پیر" to DayOfWeek.MONDAY, "سوموار" to DayOfWeek.MONDAY,
        "tuesday" to DayOfWeek.TUESDAY, "tue" to DayOfWeek.TUESDAY,
        "mangal" to DayOfWeek.TUESDAY, "منگل" to DayOfWeek.TUESDAY,
        "wednesday" to DayOfWeek.WEDNESDAY, "wed" to DayOfWeek.WEDNESDAY,
        "budh" to DayOfWeek.WEDNESDAY, "بدھ" to DayOfWeek.WEDNESDAY,
        "thursday" to DayOfWeek.THURSDAY, "thu" to DayOfWeek.THURSDAY,
        "jumerat" to DayOfWeek.THURSDAY, "jumeraat" to DayOfWeek.THURSDAY,
        "جمعرات" to DayOfWeek.THURSDAY,
        "friday" to DayOfWeek.FRIDAY, "fri" to DayOfWeek.FRIDAY,
        "juma" to DayOfWeek.FRIDAY, "jumma" to DayOfWeek.FRIDAY, "جمعہ" to DayOfWeek.FRIDAY,
        "saturday" to DayOfWeek.SATURDAY, "sat" to DayOfWeek.SATURDAY,
        "sanichar" to DayOfWeek.SATURDAY, "hafta" to DayOfWeek.SATURDAY,
        "ہفتہ" to DayOfWeek.SATURDAY,
        "sunday" to DayOfWeek.SUNDAY, "sun" to DayOfWeek.SUNDAY,
        "itwar" to DayOfWeek.SUNDAY, "اتوار" to DayOfWeek.SUNDAY,
    )

    val MONTHS: Map<String, Int> = mapOf(
        "january" to 1, "jan" to 1, "february" to 2, "feb" to 2, "march" to 3, "mar" to 3,
        "april" to 4, "apr" to 4, "may" to 5, "june" to 6, "jun" to 6, "july" to 7, "jul" to 7,
        "august" to 8, "aug" to 8, "september" to 9, "sep" to 9, "sept" to 9,
        "october" to 10, "oct" to 10, "november" to 11, "nov" to 11, "december" to 12, "dec" to 12,
    )

    // ------------------------------------------------------------- time of day

    enum class DayPart(val defaultHour: Int, val pm: Boolean) {
        MORNING(9, pm = false),
        NOON(12, pm = true),
        AFTERNOON(15, pm = true),
        EVENING(18, pm = true),
        NIGHT(21, pm = true),
    }

    val DAY_PARTS: Map<String, DayPart> = mapOf(
        "morning" to DayPart.MORNING, "subah" to DayPart.MORNING, "sub" to DayPart.MORNING,
        "صبح" to DayPart.MORNING,
        "noon" to DayPart.NOON, "dopahar" to DayPart.NOON, "dopeher" to DayPart.NOON,
        "دوپہر" to DayPart.NOON,
        "afternoon" to DayPart.AFTERNOON,
        "evening" to DayPart.EVENING, "shaam" to DayPart.EVENING, "sham" to DayPart.EVENING,
        "شام" to DayPart.EVENING,
        "night" to DayPart.NIGHT, "tonight" to DayPart.NIGHT, "raat" to DayPart.NIGHT,
        "raat ko" to DayPart.NIGHT, "رات" to DayPart.NIGHT,
    )

    /** "baje" is the Urdu o'clock marker; "bajay"/"bje" are common spellings. */
    val OCLOCK_WORDS: Set<String> = setOf("baje", "bajay", "bje", "bajey", "بجے", "o'clock", "oclock")

    // --------------------------------------------------------------- durations

    val HOUR_WORDS: Set<String> = setOf("hour", "hours", "hr", "hrs", "ghanta", "ghante", "ghanty", "گھنٹہ", "گھنٹے")
    val MINUTE_WORDS: Set<String> = setOf("minute", "minutes", "min", "mins", "minat", "منٹ")

    // -------------------------------------------------------------- recurrence

    val EVERY_WORDS: Set<String> = setOf("every", "each", "har", "ہر")
    val DAILY_WORDS: Set<String> = setOf("daily", "everyday", "rozana", "roz", "روزانہ", "روز")
    val WEEKLY_WORDS: Set<String> = setOf("weekly", "hafta war", "haftawar", "ہفتہ وار", "ہفتے")
    val MONTHLY_WORDS: Set<String> = setOf("monthly", "mahana", "maheena", "ماہانہ", "مہینے")
    val WEEKDAY_SET_WORDS: Set<String> = setOf("weekday", "weekdays", "working days", "kaam ke din")

    // ----------------------------------------------------------------- intents

    val REMIND_WORDS: Set<String> = setOf("remind", "reminder", "yaad dila", "yaad dilana", "یاد دلانا", "یاد دہانی")
    val RESCHEDULE_WORDS: Set<String> = setOf(
        "move", "reschedule", "shift", "postpone", "push", "delay",
        "badal", "badlo", "aage karo", "بدل", "ملتوی",
    )
    /**
     * Perfective forms only. "finish" and "complete" as bare infinitives are how people *create*
     * work ("I need to finish the WordPress project"), so including them here made every new task
     * look like a completion report.
     */
    val COMPLETE_WORDS: Set<String> = setOf(
        "done", "completed", "finished", "mark done", "mark complete", "mark as done",
        "ho gaya", "hogaya", "kar liya", "kr liya", "mukammal", "مکمل", "ہو گیا", "کر لیا",
    )
    val CANCEL_WORDS: Set<String> = setOf("cancel", "drop", "mansookh", "منسوخ", "cancel karo")
    val DELETE_WORDS: Set<String> = setOf("delete", "remove", "hata do", "mita do", "حذف", "مٹا")
    val SKIP_WORDS: Set<String> = setOf("skip", "skipped", "chor do", "chhor do", "چھوڑ")
    val PROGRESS_WORDS: Set<String> = setOf(
        "progress", "half", "halfway", "adha", "aadha", "still working", "paused", "pause",
        "آدھا", "جاری",
    )
    val REMEMBER_WORDS: Set<String> = setOf(
        "remember", "note that", "keep in mind", "yaad rakho", "yaad rakhna", "yaad rakhein",
        "یاد رکھنا", "یاد رکھو",
    )
    val HABIT_WORDS: Set<String> = setOf(
        "i usually", "i normally", "i generally", "usually i", "aam taur par", "عام طور پر",
    )
    val QUESTION_WORDS: Set<String> = setOf(
        "what", "when", "how much", "how many", "show", "list", "which",
        "kya", "kab", "kitna", "kitni", "dikhao", "batao",
        "کیا", "کب", "کتنا", "کتنی", "دکھاؤ", "بتاؤ",
    )

    // ------------------------------------------------ title clean-up filler

    /** Leading phrases that carry intent but no task content. Order matters: longest first. */
    val LEADING_FILLER: List<String> = listOf(
        "please remind me to", "please remind me", "can you remind me to", "remind me to",
        "remind me that", "remind me", "i need to", "i have to", "i want to", "i must",
        "i am going to", "i'm going to", "i will", "i'll", "i need", "we need to",
        "make a task to", "make a task", "create a task to", "create a task",
        "add a task to", "add a task", "schedule a", "schedule", "set a reminder to",
        "set a reminder", "please", "mujhe", "mujhay", "mujhe ek", "main", "mein",
        "مجھے", "میں", "براہ کرم",
    )

    /** Trailing phrases that close an Urdu/Roman-Urdu sentence without adding content. */
    val TRAILING_FILLER: List<String> = listOf(
        "karna hai", "karni hai", "krna hai", "karna h", "karna he", "karney hain",
        "karne hain", "kar lena hai", "ka kaam karna hai", "ki zarurat hai",
        "کرنا ہے", "کرنی ہے", "کرنا ھے", "کی ضرورت ہے", "ہے",
        "ka reminder dena", "ka reminder dena hai", "reminder dena", "ka reminder set karo",
        "کا ریمائنڈر دینا", "یاد دلانا",
    )

    /**
     * Prepositions and particles that are left dangling at the edge of a title once the time or date
     * they introduced has been removed ("... for studying" -> "studying").
     */
    val ORPHAN_EDGE_WORDS: Set<String> = setOf(
        "at", "from", "to", "on", "for", "by", "in", "of", "and", "till", "until", "around",
        "se", "tak", "ko", "ka", "ki", "ke", "par", "mein",
        "سے", "تک", "کو", "کا", "کی", "کے", "پر", "میں",
    )

    // ----------------------------------------------------------------- helpers

    fun isNumberWord(token: String): Boolean = NUMBER_WORDS.containsKey(token)

    fun numberOf(token: String): Int? = NUMBER_WORDS[token] ?: token.toIntOrNull()
}
