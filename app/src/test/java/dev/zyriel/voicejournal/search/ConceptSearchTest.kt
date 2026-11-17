package dev.zyriel.voicejournal.search

import dev.zyriel.voicejournal.TestPaths
import dev.zyriel.voicejournal.data.JournalEntry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.channels.FileChannel

/**
 * The bar for "smart" search: finding an entry by what it is, when the
 * entry never says the word. A ramble about clocks on fast ships has to
 * come back for "physics"; a recited sonnet for "poems".
 *
 * The journal is 36 short entries: twelve everyday ones and 24 whose
 * subject or kind is never named in them (three physics explanations,
 * four recited poems, a prayer, a hymn, a proof, a dream, a joke...).
 * Each of 35 queries is labelled with the entries a person would expect
 * back. Everything here runs the bundled model with the keyword side
 * switched off, so a pass is the model's doing.
 *
 * This set is what chose the model. Mean average precision on it, int8
 * ONNX builds, same pipeline: bge-small 0.74 (the previous model),
 * gte-small 0.84, EmbeddingGemma-300m 0.85 to 0.89, gte-base 0.89 (this
 * one). It is one person's 35 queries, so differences of a few
 * hundredths between models mean nothing; the gap to the old model does.
 *
 * What it still gets wrong is written into the assertions rather than
 * hidden: for "poems" a prayer and a hymn rank among the poems, and two
 * of the four poems fall just under the result floor ("poem I recited"
 * brings back all four). Telling a poem from
 * a prayer is about the kind of text, which this size of embedding model
 * only half sees. Lines starting CON print the full table.
 */
class ConceptSearchTest {

    private val journal: Map<String, String> = linkedMapOf(
        "thesis1" to "Had a long talk with my advisor about the thesis today. She wants the introduction rewritten before the committee meets.",
        "thesis2" to "Felt anxious all afternoon about the dissertation deadline. I keep putting off the methods chapter.",
        "run" to "Went for a run by the river this morning, about five kilometres. Legs felt heavy but the weather was perfect.",
        "pasta" to "Made pasta with roasted tomatoes for dinner and tried the new bread recipe. The crust came out too dark.",
        "mom" to "Called mom. She told me about the garden and that dad has been fixing the fence all week.",
        "deploy" to "Work was frustrating. The deploy failed twice and I spent hours chasing a bug in the payment service.",
        "dentist" to "Dentist appointment on Tuesday. I need to remember to ask about the wisdom tooth.",
        "denver" to "Couldn't sleep last night, kept thinking about whether to take the job offer in Denver.",
        "lisbon" to "Booked flights to Lisbon for our anniversary in May. Need to find a place to stay near the old town.",
        "car" to "The car made a strange grinding noise again. Taking it to the mechanic on Friday.",
        "novel" to "Read a few chapters of the novel before bed. The main character finally left the island.",
        "budget" to "Budget check: spent too much on eating out this month, need to cut back on takeaway.",
        "relativity" to "I keep thinking about why time slows down when you go really fast. If you were on a ship moving close to the speed of light your clock would tick slower than mine back on Earth. And gravity does the same thing, a heavy star bends the space around it so even light curves. Einstein worked that out with thought experiments about trains and elevators.",
        "quantum" to "Talked with Sam for an hour about how an electron can be in two places until you measure it. The cat in the box that is alive and dead at once. He says the wave function collapses when you look, I said maybe every outcome happens in a different branch and we only see one.",
        "entropy" to "So the reason a cup of coffee cools down and never heats up by itself is that there are just vastly more ways for the energy to be spread out than concentrated. Heat flows from hot to cold because disorder always goes up. That's also why you can't build a machine that runs forever.",
        "frost" to "Two roads diverged in a yellow wood, and sorry I could not travel both and be one traveler, long I stood and looked down one as far as I could to where it bent in the undergrowth. Then took the other, as just as fair, and having perhaps the better claim, because it was grassy and wanted wear.",
        "sonnet" to "Shall I compare thee to a summer's day? Thou art more lovely and more temperate. Rough winds do shake the darling buds of May, and summer's lease hath all too short a date. Sometime too hot the eye of heaven shines, and often is his gold complexion dimmed.",
        "tyger" to "Tiger, tiger, burning bright in the forests of the night, what immortal hand or eye could frame thy fearful symmetry? In what distant deeps or skies burnt the fire of thine eyes? On what wings dare he aspire? What the hand dare seize the fire?",
        "dickinson" to "Because I could not stop for death, he kindly stopped for me. The carriage held but just ourselves and immortality. We slowly drove, he knew no haste, and I had put away my labor and my leisure too, for his civility.",
        "calculus" to "Finally got why the slope of e to the x is e to the x. If you take the limit of the difference quotient the function factors out of itself. And the area under one over x being the natural log clicked too once I drew the rectangles.",
        "primes" to "Tried to prove tonight that there are infinitely many primes. You multiply all of them together and add one, and the new number is not divisible by any on your list, so the list was never complete. Euclid did this two thousand years ago.",
        "lordsprayer" to "Our Father, who art in heaven, hallowed be thy name. Thy kingdom come, thy will be done, on earth as it is in heaven. Give us this day our daily bread and forgive us our trespasses as we forgive those who trespass against us.",
        "freewill" to "If everything I do is caused by what came before, my genes, how I was raised, the state of my brain a second ago, then in what sense did I choose anything? But it still feels wrong to say nobody deserves praise or blame. Maybe choosing just means acting from your own reasons.",
        "dream" to "I was in my grandmother's house but the rooms kept rearranging, and I could float down the staircase without touching it. My old teacher was there asking for homework I never did. Then I woke up at four with my heart pounding.",
        "insulin" to "The doctor explained my blood sugar spikes because my cells stopped responding to insulin properly. She wants me checking my glucose every morning and cutting way back on bread and sweets, and to come back in three months for another A1C.",
        "rome" to "Been reading about how the western empire came apart. The legions were stretched thin along the Rhine, emperors were being murdered every couple of years, and by the time the Goths sacked the city in 410 it had been hollow for a century.",
        "recipe" to "Okay so brown two onions in butter, add three cloves of garlic, then the rice, stir until it's glassy. Pour in a ladle of stock at a time and keep stirring for twenty minutes. Finish with parmesan and a squeeze of lemon.",
        "code" to "Spent the evening figuring out why the list kept getting modified while I was looping over it. Turns out I was passing a reference instead of a copy into the function. Rewrote it with a map and a filter and it is half the length now.",
        "joke" to "Okay I have to remember this one. A horse walks into a bar and the bartender says, why the long face? And the one about the two fish in a tank, one turns to the other and says, do you know how to drive this thing?",
        "grief" to "It's been a year since grandpa passed. I still reach for the phone on Sundays to call him. Found his old watch in the drawer today and just sat on the floor holding it for a while.",
        "hymn" to "Amazing grace, how sweet the sound, that saved a wretch like me. I once was lost, but now am found, was blind, but now I see. 'Twas grace that taught my heart to fear, and grace my fears relieved.",
        "stars" to "Stayed out on the roof until two. You could see the whole band of the Milky Way and Jupiter with four of its moons through the binoculars. Saw three meteors in an hour.",
        "guitar" to "Practiced the F barre chord for forty minutes and my fingers are raw. The change from C to F is still slow but I got through the whole first verse without stopping.",
        "interview" to "Second round with the hiring manager went okay. They asked about a time I disagreed with a teammate and I rambled. They said I'd hear back by Thursday.",
        "kids" to "Maya took her first steps today, four of them, from the couch to my hands. She looked so surprised at herself and then sat down and laughed.",
        "garden" to "Planted the tomatoes and basil in the raised bed. The soil is still cold so I covered them overnight. Slugs already got to the lettuce.",
    )

    /** Query -> keys of the entries a person would expect back. */
    private val expected: Map<String, Set<String>> = linkedMapOf(
        "physics" to setOf("relativity", "quantum", "entropy"),
        "science" to setOf("relativity", "quantum", "entropy", "insulin", "stars"),
        "poems" to setOf("frost", "sonnet", "tyger", "dickinson"),
        "poetry" to setOf("frost", "sonnet", "tyger", "dickinson"),
        "poem I recited" to setOf("frost", "sonnet", "tyger", "dickinson"),
        "math" to setOf("calculus", "primes"),
        "mathematics" to setOf("calculus", "primes"),
        "prayer" to setOf("lordsprayer"),
        "religion" to setOf("lordsprayer", "hymn"),
        "philosophy" to setOf("freewill"),
        "dreams" to setOf("dream"),
        "health" to setOf("dentist", "insulin"),
        "medical" to setOf("dentist", "insulin"),
        "history" to setOf("rome"),
        "cooking" to setOf("pasta", "recipe"),
        "programming" to setOf("deploy", "code"),
        "jokes" to setOf("joke"),
        "something funny" to setOf("joke"),
        "death" to setOf("dickinson", "grief"),
        "loss of a family member" to setOf("grief"),
        "singing" to setOf("hymn"),
        "songs" to setOf("hymn"),
        "music" to setOf("hymn", "guitar"),
        "astronomy" to setOf("stars"),
        "career" to setOf("denver", "interview"),
        "my daughter" to setOf("kids"),
        "family" to setOf("mom", "grief", "kids"),
        "shakespeare" to setOf("sonnet"),
        "einstein's theory" to setOf("relativity"),
        "thermodynamics" to setOf("entropy"),
        "literature" to setOf("novel", "frost", "sonnet", "tyger", "dickinson"),
        "exercise" to setOf("run"),
        "travel plans" to setOf("lisbon"),
        "money" to setOf("budget"),
        "car repair" to setOf("car"),
    )

    /** Subjects this journal never touches. */
    private val absent = listOf(
        "skiing in the Alps", "my cat's birthday", "presidential election", "learning to play the violin", "chemistry", "football", "painting", "wedding", "tax return", "moving house",
    )

    private class Ranked(val keys: List<String>, val returned: List<String>)

    @Test
    fun entriesAreFoundByWhatTheyAreAbout() = runBlocking {
        val engine = OnnxEmbeddingEngine(
            model = FileChannel.open(TestPaths.mainAsset("models/gte_base_q8.onnx").toPath()).use {
                it.map(FileChannel.MapMode.READ_ONLY, 0, it.size())
            },
            vocab = WordPieceTokenizer(TestPaths.mainAsset("models/bert_vocab.txt").inputStream()),
        )
        val keys = journal.keys.toList()
        val entries = keys.mapIndexed { i, key ->
            JournalEntry(id = i.toLong(), transcript = journal.getValue(key), timestampMs = i.toLong(), audioPath = "",
                embedding = engine.embed(journal.getValue(key))!!)
        }

        suspend fun run(query: String): Ranked {
            val qv = engine.embedQuery(query)!!
            val order = entries.map { it to VectorSearch.bestCosine(qv, it.embedding!!)!! }
                .sortedByDescending { it.second }.map { keys[it.first.id.toInt()] }
            // A made-up query string defeats the keyword side: meaning only.
            val returned = HybridSearch.rank("zzqx", qv, entries, 20).map { keys[it.entry.id.toInt()] }
            return Ranked(order, returned)
        }

        var apSum = 0.0
        var recallSum = 0.0
        var precisionSum = 0.0
        var topHits = 0
        val results = HashMap<String, Ranked>()
        for ((query, want) in expected) {
            val r = run(query)
            results[query] = r
            var hits = 0
            var ap = 0.0
            r.keys.forEachIndexed { i, key -> if (key in want) { hits++; ap += hits.toDouble() / (i + 1) } }
            ap /= want.size
            apSum += ap
            if (r.keys.first() in want) topHits++
            val got = r.returned.toSet()
            recallSum += got.intersect(want).size.toDouble() / want.size
            precisionSum += if (got.isEmpty()) 0.0 else got.intersect(want).size.toDouble() / got.size
            println("CON %-24s AP=%.2f returned=%s".format(query, ap, r.returned))
        }
        val n = expected.size
        val map = apSum / n
        val recall = recallSum / n
        val precision = precisionSum / n
        var absentResults = 0
        for (query in absent) {
            val r = run(query)
            absentResults += r.returned.size
            println("CON absent %-24s returned=%s".format(query, r.returned))
        }
        println("CON MAP=%.3f top1=%d/%d recall=%.2f precision=%.2f absentResults=%d".format(map, topHits, n, recall, precision, absentResults))

        // The headline cases, exactly as asked for.
        val physics = results.getValue("physics")
        assertEquals("'physics' must lead with the three entries that never say it",
            setOf("relativity", "quantum", "entropy"), physics.keys.take(3).toSet())
        assertTrue("'physics' must return all three, got ${physics.returned}",
            physics.returned.containsAll(listOf("relativity", "quantum", "entropy")))
        val poems = setOf("frost", "sonnet", "tyger", "dickinson")
        for (query in listOf("poems", "poetry")) {
            val r = results.getValue(query)
            assertTrue("'$query' must lead with two poems, got ${r.keys.take(3)}", r.keys.take(2).all { it in poems })
            // Known limit: the prayer and the hymn come back among them, and
            // the two least poem-like poems sit just under the floor.
            assertTrue("'$query' returned something that is not verse: ${r.returned}",
                r.returned.all { it in poems || it == "lordsprayer" || it == "hymn" })
        }
        val recited = results.getValue("poem I recited")
        assertTrue("'poem I recited' must return all four poems, got ${recited.returned}", recited.returned.containsAll(poems))

        // The set as a whole. Measured: MAP 0.89, top hit right 32 of 35,
        // recall 0.90, precision 0.62, 11 results over the 10 absent queries.
        assertTrue("mean average precision fell to $map", map >= 0.85)
        assertTrue("top result right for only $topHits of $n", topHits >= 30)
        assertTrue("recall of the returned set fell to $recall", recall >= 0.85)
        assertTrue("precision of the returned set fell to $precision", precision >= 0.55)
        assertTrue("absent subjects returned $absentResults entries", absentResults <= 15)
        engine.close()
    }
}
