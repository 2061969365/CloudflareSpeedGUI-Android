package com.cfst.android.ui.components

import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.ui.graphics.vector.ImageVector

val CopyIcon: ImageVector by lazy {
    materialIcon(name = "CopyIcon") {
        materialPath {
            moveTo(2f, 12f)
            horizontalLineTo(14f)
            verticalLineTo(22f)
            horizontalLineTo(2f)
            verticalLineTo(12f)
            close()
            moveTo(10f, 2f)
            horizontalLineTo(22f)
            verticalLineTo(14f)
            horizontalLineTo(10f)
            verticalLineTo(2f)
            close()
        }
    }
}