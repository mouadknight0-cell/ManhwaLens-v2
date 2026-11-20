package com.manhwalens.app.pipeline
class ConnectedDialogueTest { @org.junit.Test fun needsPhraseBoundary(){ org.junit.Assert.assertNull(ConnectedDialogue.split("أنا هنا الآن","I am","right here")); org.junit.Assert.assertEquals("أنا هنا،" to "لا تقلق",ConnectedDialogue.split("أنا هنا، لا تقلق","I am here","do not worry")) } }
