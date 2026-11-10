package ir.elviran.sam

import ir.elviran.sam.voice.engine.PersianSpeech
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PersianSpeechTest {
    @Test fun converts_basic_numbers() {
        assertEquals("صفر", PersianSpeech.numberToWords(0))
        assertEquals("یک", PersianSpeech.numberToWords(1))
        assertEquals("بیست و پنج", PersianSpeech.numberToWords(25))
        assertEquals("صد و سی و دو", PersianSpeech.numberToWords(132))
    }

    @Test fun converts_large_numbers() {
        assertEquals("یک هزار", PersianSpeech.numberToWords(1000))
        assertTrue(PersianSpeech.numberToWords(1_250_000).contains("میلیون"))
        assertTrue(PersianSpeech.numberToWords(2_000_000_000).contains("میلیارد"))
    }

    @Test fun converts_persian_digits_in_sentence() {
        val out = PersianSpeech.speakNumbersIn("مبلغ ۱۲۰ تومان")
        assertTrue(out.contains("صد و بیست"))
    }

    @Test fun jalali_date_to_words() {
        val out = PersianSpeech.jalaliDateToWords("1405/09/15")
        assertEquals("پانزده آذر یک هزار و چهارصد و پنج", out)
    }

    @Test fun normalize_pipeline_handles_date_then_numbers() {
        val out = PersianSpeech.normalizeForSpeech("تحویل ۱۴۰۵/۰۹/۱۵ ساعت 8")
        assertTrue(out.contains("آذر"))
        assertTrue(out.contains("هشت"))
    }
}
