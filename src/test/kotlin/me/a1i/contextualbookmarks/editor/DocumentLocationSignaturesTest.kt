package me.a1i.contextualbookmarks.editor

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import me.a1i.contextualbookmarks.model.LocationSignatures

class DocumentLocationSignaturesTest : BasePlatformTestCase() {
    fun testHashesOnlyCurrentAndNeighboringLineValues() {
        val document = myFixture.configureByText("signature.txt", "ignored\nprevious\ntarget\nnext\nalso ignored").viewProvider.document!!

        val signature = DocumentLocationSignatures.fromDocument(document, 2)

        assertEquals(LocationSignatures.hash("previous"), signature.previousLineHash)
        assertEquals(LocationSignatures.hash("target"), signature.currentLineHash)
        assertEquals(LocationSignatures.hash("next"), signature.nextLineHash)
    }
}
