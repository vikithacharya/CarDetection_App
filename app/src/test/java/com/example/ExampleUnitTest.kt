package com.example

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleUnitTest {
  @Test
  fun testModelListNotEmpty() {
    assertTrue(MODELS.isNotEmpty())
    assertTrue(MODELS.contains("gemini-flash-latest"))
  }

  @Test
  fun testPromptStructure() {
    assertTrue(PROMPT.contains("Identify the main car in this image"))
    assertTrue(PROMPT.contains("is_car"))
  }

  @Test
  fun testJsonParsing() {
    val sampleJson = """
      {
        "is_car": true,
        "make": "Chevrolet",
        "model": "Corvette Z06",
        "year_range": "2023 - 2025",
        "colour": "Torch Red",
        "colour_hex": "#C8102E",
        "confidence": 97
      }
    """.trimIndent()

    val obj = JSONObject(sampleJson)
    assertTrue(obj.optBoolean("is_car"))
    assertEquals("Chevrolet", obj.optString("make"))
    assertEquals("Corvette Z06", obj.optString("model"))
    assertEquals(97, obj.optInt("confidence"))
  }
}
