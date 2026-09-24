package com.aifacebookdetector

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

class DetectorAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit
}
