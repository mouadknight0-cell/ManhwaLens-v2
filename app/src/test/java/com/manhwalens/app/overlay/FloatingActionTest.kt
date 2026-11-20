package com.manhwalens.app.overlay

class FloatingActionTest { @org.junit.Test fun threeActionsInReadingOrder() { org.junit.Assert.assertEquals(listOf("START","STOP","CLOSE"), FloatingAction.entries.map { it.name }) } }
