package com.houseoftech.voicelock

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the pure phrase-match decision that VoskEngine runs on every Vosk
 * result/partial JSON. No Vosk, no Android -- just the string logic that decides
 * whether the wake phrase was heard.
 */
class VoskEngineTest {

    private val phrase = "unlock my phone"

    @Test
    fun `exact partial matches`() {
        assertTrue(VoskEngine.phraseMatched("""{"partial" : "unlock my phone"}""", phrase))
    }

    @Test
    fun `final text field matches`() {
        assertTrue(VoskEngine.phraseMatched("""{"text" : "unlock my phone"}""", phrase))
    }

    @Test
    fun `phrase embedded in a longer utterance matches`() {
        assertTrue(VoskEngine.phraseMatched("""{"text" : "please unlock my phone now"}""", phrase))
    }

    @Test
    fun `case and extra whitespace are normalised`() {
        assertTrue(VoskEngine.phraseMatched("""{"text" : "Unlock  My   Phone"}""", phrase))
    }

    @Test
    fun `partial prefix of the phrase does not match`() {
        // Mid-utterance Vosk often emits a growing prefix; it must not fire early.
        assertFalse(VoskEngine.phraseMatched("""{"partial" : "unlock my"}""", phrase))
    }

    @Test
    fun `empty partial does not match`() {
        assertFalse(VoskEngine.phraseMatched("""{"partial" : ""}""", phrase))
    }

    @Test
    fun `unrelated speech does not match`() {
        assertFalse(VoskEngine.phraseMatched("""{"text" : "what time is it"}""", phrase))
    }
}
