package com.personal.assistant.core.ai

import java.time.format.DateTimeFormatter

/**
 * Builds the instruction prompt for a local model.
 *
 * Two decisions worth stating. First, the model is asked for JSON in a closed vocabulary that maps
 * one-to-one onto [AssistantCommand]; it is never asked to write a reply for the user or to decide
 * what to store. Second, the prompt keeps relative wording ("tomorrow", "kal") intact rather than
 * asking for a resolved calendar date: a sub-billion-parameter model is far worse at date arithmetic
 * than [com.personal.assistant.core.nlp.DateResolver], so the app resolves dates itself and only asks
 * the model for the part it is actually good at -- deciding what the sentence is asking for.
 */
object PromptBuilder {

    private val DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val TIME = DateTimeFormatter.ofPattern("HH:mm")

    const val SCHEMA: String = """{
  "intent": "create_task | reschedule | change_status | set_progress | delete_task | remember | query | clarify | small_talk",
  "title": "short task name, in the user's own words",
  "date_phrase": "the user's own date wording, e.g. tomorrow, kal, next monday, 2026-09-18",
  "time_phrase": "the user's own time wording, e.g. 4 pm, 16:00, 4 baje, shaam 7 baje",
  "end_time_phrase": "optional end of the task, same format",
  "duration_minutes": 0,
  "priority": "LOW | NORMAL | HIGH | URGENT",
  "category": "one of the known categories, or null",
  "reminder_minutes": 30,
  "recurrence": {"frequency": "DAILY | WEEKLY | MONTHLY", "interval": 1, "days_of_week": ["MONDAY"]},
  "status": "COMPLETED | CANCELLED | SKIPPED | POSTPONED | MISSED",
  "progress": 0,
  "target": "which existing task the user means, in their words",
  "memory": "the fact to remember",
  "query_kind": "PLAN | COMPLETED | MISSED | UPCOMING | TIME_SPENT | SEARCH | DAILY_REPORT | WEEKLY_REPORT | MONTHLY_REPORT",
  "question": "what to ask back when something essential is missing"
}"""

    fun system(context: AiContext): String = buildString {
        appendLine("You convert a personal assistant user's message into one JSON object. Output JSON only.")
        appendLine()
        appendLine("Today is ${context.now.format(DATE)} (${context.now.dayOfWeek}) and the time is ${context.now.format(TIME)}.")
        appendLine("The user writes in English, Urdu, Roman Urdu, or a mix. Keep their wording in the text fields.")
        appendLine("Do not calculate dates. Copy the user's date and time wording into date_phrase and time_phrase.")
        appendLine("Include only the fields that apply. Never invent a task the user did not mention.")
        appendLine()
        appendLine("Schema:")
        appendLine(SCHEMA)
        if (context.categoryNames.isNotEmpty()) {
            appendLine()
            appendLine("Known categories: ${context.categoryNames.joinToString(", ")}")
        }
        if (context.recentTaskTitles.isNotEmpty()) {
            appendLine("Recent tasks: ${context.recentTaskTitles.take(10).joinToString("; ")}")
        }
        if (context.memories.isNotEmpty()) {
            appendLine()
            appendLine("Things the user asked you to remember:")
            context.memories.take(10).forEach { appendLine("- $it") }
        }
        context.pendingClarification?.let { pending ->
            appendLine()
            appendLine("You previously asked: \"${pending.question}\". The next message may be the answer.")
        }
        appendLine()
        appendLine("Examples:")
        appendLine(EXAMPLES)
    }

    fun user(input: String): String = input.trim()

    private val EXAMPLES: String = listOf(
        """User: Tomorrow at 10 AM I have to work on the website.
{"intent":"create_task","title":"Work on website","date_phrase":"tomorrow","time_phrase":"10 am","reminder_minutes":30}""",
        """User: Kal 5 baje mujhe client ko call karne ka reminder dena.
{"intent":"create_task","title":"Client ko call","date_phrase":"kal","time_phrase":"5 baje","reminder_minutes":30}""",
        """User: Every Monday at 9 AM I work on my AI project.
{"intent":"create_task","title":"AI project","time_phrase":"9 am","recurrence":{"frequency":"WEEKLY","interval":1,"days_of_week":["MONDAY"]}}""",
        """User: Move my website task to tomorrow at 5.
{"intent":"reschedule","target":"website","date_phrase":"tomorrow","time_phrase":"5"}""",
        """User: I've finished about half.
{"intent":"set_progress","progress":50}""",
        """User: I usually work on my WordPress projects at night.
{"intent":"remember","memory":"Usually works on WordPress projects at night"}""",
        """User: What did I miss yesterday?
{"intent":"query","query_kind":"MISSED","date_phrase":"yesterday"}""",
    ).joinToString("\n\n")
}
