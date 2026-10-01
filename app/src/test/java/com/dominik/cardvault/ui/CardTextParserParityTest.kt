package com.dominik.cardvault.ui

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Characterization of existing parser output, NOT OCR/card-identity accuracy.
 * Digest captured from unchanged production at 24e443d, then checked again
 * before the mechanical Regex hoist. Do not silently regenerate this baseline.
 * No recording mode, registry mutation, legacy parser copy, Android or network.
 */
class CardTextParserParityTest {
    @Test fun completeParserResultsMatchFrozenUnchangedBaseline() {
        val inputs = cases()
        val observed = inputs.map(::observe)
        val bytes = snapshot(inputs, observed)
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        println("CARD_TEXT_PARSER_PARITY_BASELINE cases=${inputs.size} bytes=${bytes.size} sha256=$digest")
        assertEquals("Freeze only the unchanged-product baseline; do not bless changed output", EXPECTED_SHA256, digest)
    }

    @Test fun concurrentCallsMatchEveryCompleteSequentialResult() {
        val inputs = cases()
        val expected = inputs.map(::observe)
        val executor = Executors.newFixedThreadPool(4)
        val ready = CyclicBarrier(4)
        try {
            // Four finite tasks, not an unbounded executor queue. The timeout is
            // a cleanup/liveness bound, never a performance requirement.
            val tasks = (0 until 4).map { lane -> Callable {
                ready.await(5, TimeUnit.SECONDS)
                (lane until inputs.size step 4).map { index ->
                    check(!Thread.currentThread().isInterrupted) { "Parity task interrupted" }
                    index to observe(inputs[index])
                }
            } }
            val futures = executor.invokeAll(tasks, 30, TimeUnit.SECONDS)
            val visited = mutableSetOf<Int>()
            futures.forEach { future ->
                // Cancellation, failure or any incomplete lane is a test failure.
                future.get().forEach { (index, actual) ->
                    assertTrue("Duplicate case ${inputs[index].id}", visited.add(index))
                    assertEquals(inputs[index].id, expected[index], actual)
                }
            }
            assertEquals(inputs.indices.toSet(), visited)
        } finally {
            executor.shutdownNow()
            assertTrue("Parity executor did not stop", executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    private data class Input(
        val id: String, val numberText: String, val texts: Map<CardScript, String>,
        val nameRoi: String?, val numberRoi: String?, val setRoi: String?,
    )
    private data class Observed(val number: CardNumberParser.Evidence?, val analysis: CardTextAnalysis)
    private data class Base(
        val id: String, val name: String, val borders: List<String>,
        val body: List<String> = emptyList(), val script: CardScript = CardScript.LATIN,
        val extraTexts: Map<CardScript, String> = emptyMap(),
    )

    private fun observe(input: Input): Observed {
        val number = CardNumberParser.find(input.numberText)
        val analysis = CardTextParser.parse(input.texts, input.nameRoi, input.numberRoi, input.setRoi)
        assertEquals("All non-parser fields remain default: ${input.id}", CardTextAnalysis(
            lines = analysis.lines, collectorNumber = analysis.collectorNumber,
            setIdentifier = analysis.setIdentifier, likelyScript = analysis.likelyScript,
            searchQueries = analysis.searchQueries, numberConfidence = analysis.numberConfidence,
            hasNameRoiEvidence = analysis.hasNameRoiEvidence,
        ), analysis)
        return Observed(number, analysis)
    }

    private fun cases(): List<Input> {
        // Verified shipped codes/promo namespaces only. Unknown tokens are longer
        // than the registry's 2..6 registration limit; never use mutable ZZQ/UND.
        val bases = listOf(
            Base("empty", "", emptyList()),
            Base("blank", " \t ", listOf(" ", "\u00a0")),
            Base("modern", "Pikachu ex", listOf("PBL DE 117/084")),
            Base("leading-de", "Pikachu ex", listOf("DE PBL 117/084")),
            Base("hyphen-de", "Flamara", listOf("CRI-DE 087/086")),
            Base("underscore-de", "Flamara", listOf("CRI__DE__087/086")),
            Base("compact", "Glurak ex", listOf("OBF098/197")),
            Base("anniversary", "Mew", listOf("J 30C DE 065/128")),
            Base("noisy-border", "Flamara", listOf("CRI---DE o87/o86")),
            Base("slash-y", "Pikachu", listOf("PBL o94YO84")),
            Base("slash-v", "Pikachu", listOf("PBL 09V080")),
            Base("tg-subset", "Galeriekarte", listOf("TG05/TG30", "TG05/TG30")),
            Base("gg-subset", "Galeriekarte", listOf("GG01/GG70", "GG01/GG70")),
            Base("mep", "Promokarte", listOf("MEP071")),
            Base("svp", "Promokarte", listOf("SVP173")),
            Base("split-promo", "Promokarte", listOf("MEP", "DE", "071")),
            Base("regulation-promo", "Promokarte", listOf("JMEP DE 071")),
            Base("hp", "Pikachu", listOf("HP PBL 117/084", "PBL 117/084")),
            Base("hp-punctuation", "Pikachu", listOf("(hP) PBL 117/084")),
            Base("kp-case", "Pikachu", listOf("kP PBL 117/084", "KP PBL 117/084")),
            Base("nonword-noise", "Pikachu", listOf("QQQQQQQQHP PBL 117/084", "KPQQQQQQQQ PBL 117/084")),
            Base("unicode-word-boundary", "Pikachu", listOf("éHPé PBL 117/084", "éKPé PBL 117/084")),
            Base("consensus", "Flamara", listOf("037/086", "087/086", "087/086")),
            Base("tie-forward", "Pikachu", listOf("OBF 198/197", "PBL 117/084")),
            Base("tie-reverse", "Pikachu", listOf("PBL 117/084", "OBF 198/197")),
            Base("rules", "Glurak ex", listOf("PBL 117/084"), listOf("Attacke 200", "KP 330", "Rückzug")),
            Base("diacritics", "Flabébé Großklaue", listOf("CRI 087/086")),
            Base("japanese", "ピカチュウ", listOf("025/165"), script = CardScript.JAPANESE),
            Base("chinese", "皮卡丘收藏", listOf("025/165"), script = CardScript.CHINESE),
            Base("long-unknown", "Sammlerkarte", listOf("QQQQQQQQQQQQ 123/456")),
            Base("length-boundary", "X".repeat(81), listOf("PBL 117/084"),
                listOf("A".repeat(79) + "   B", "A".repeat(77) + "\t\tB")),
            Base("mixed-script", "Igamaro", listOf("PBL 117/084"),
                extraTexts = linkedMapOf(CardScript.JAPANESE to "の", CardScript.CHINESE to "卡")),
        )
        val separators = listOf("\n", "\r\n", " \n ", "\t", "\u00a0", "\u2003")
        assertEquals(32, bases.size)
        return bases.flatMap { base -> separators.flatMapIndexed { separatorIndex, separator ->
            val border = base.borders.joinToString(separator)
            val text = (listOf(base.name) + base.borders + base.body).joinToString(separator)
            val texts = linkedMapOf(base.script to text).apply { putAll(base.extraTexts) }
            (0 until 5).map { mode ->
                val rois = when (mode) {
                    0 -> Triple(null, null, null)
                    1 -> Triple("", "", "")
                    2 -> Triple(base.name, border, border)
                    3 -> Triple(base.name, border, "PBL 117/084${separator}OBF 198/197")
                    else -> Triple(null, "", border)
                }
                Input("${base.id}/separator-$separatorIndex/roi-$mode", border, texts,
                    rois.first, rois.second, rois.third)
            }
        } }.also { inputs ->
            assertEquals(960, inputs.size)
            assertEquals(inputs.size, inputs.map(Input::id).toSet().size)
        }
    }

    private fun snapshot(inputs: List<Input>, observed: List<Observed>): ByteArray {
        assertEquals(inputs.size, observed.size)
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.frame("card-text-parser-parity-v1")
            out.writeInt(inputs.size)
            inputs.zip(observed).forEach { (input, result) ->
                out.frame(input.id)
                out.frame(input.numberText)
                out.writeInt(input.texts.size)
                input.texts.forEach { (script, text) ->
                    out.frame(script.name)
                    out.frame(text)
                }
                out.frame(input.nameRoi)
                out.frame(input.numberRoi)
                out.frame(input.setRoi)
                out.frame(result.number?.toString())
                out.frame(result.number?.value)
                out.frame(result.number?.confidence?.toRawBits()?.toString())
                out.frame(result.number?.supportingReads?.toString())
                out.frame(result.analysis.toString())
                // Preserve list boundaries as well: data-class toString alone
                // does not distinguish a comma inside one string from two items.
                out.frames(result.analysis.lines)
                out.frames(result.analysis.searchQueries)
                out.frame(result.analysis.collectorNumber)
                out.frame(result.analysis.setIdentifier)
                out.frame(result.analysis.likelyScript?.name)
                out.frame(result.analysis.numberConfidence.toRawBits().toString())
                out.frame(result.analysis.hasNameRoiEvidence.toString())
            }
        }
        return bytes.toByteArray()
    }

    private fun DataOutputStream.frame(value: String?) {
        if (value == null) { writeInt(-1); return }
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeInt(bytes.size)
        write(bytes)
    }
    private fun DataOutputStream.frames(values: List<String>) {
        writeInt(values.size)
        values.forEach { frame(it) }
    }

    private companion object {
        // 960 cases / 1,198,843 framed bytes from the unchanged parser on 2026-10-01.
        // Product SHA256: 2efb2b0cec6ad50e93cb6e3ec9bdc676f27c1292eb42f2c4281959e9ba7df0a8.
        const val EXPECTED_SHA256 = "b39195cd95eaff9566b0b7c866831b0a3fd5e97273b92509d61bfa695bda5a4f"
    }
}
