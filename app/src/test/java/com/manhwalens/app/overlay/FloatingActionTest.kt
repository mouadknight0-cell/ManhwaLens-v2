package com.manhwalens.app.overlay

class FloatingActionTest { @org.junit.Test fun threeActionsInReadingOrder() { org.junit.Assert.assertEquals(listOf("تشغيل الترجمة","إيقاف","إغلاق"), FloatingAction.entries.map { it.label }) } }
