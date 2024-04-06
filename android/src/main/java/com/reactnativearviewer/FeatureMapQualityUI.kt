package com.reactnativearviewer

import android.opengl.Matrix
import com.google.ar.core.Pose
import com.google.ar.core.Session.FeatureMapQuality

/*
* Copyright 2020 Google LLC
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
*      http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
*/
//import com.google.ar.core.examples.java.common.rendering.ObjectRenderer

/** Helper class to display the Feature Map Quality UI for the Persistent Cloud Anchor Sample.  */
internal class FeatureMapQualityUi private constructor(
    val isHorizontal: Boolean,
    objectRenderer: ObjectRenderer
) {
    val arcStartRad: Double
    val arcEndRad: Double
    val numBars: Int
    val radius: Float
    var objectRenderer: ObjectRenderer
    val bars: Array<QualityBar?>
    var featureMapQualityUIPose: Pose? = null

    internal enum class Quality {
        UNKNOWN, INSUFFICIENT, SUFFICIENT, GOOD
    }

    internal inner class QualityBar(rad: Double) {
        private val localPose: Pose
        private val modelMatrix: FloatArray
        var quality: FeatureMapQualityUi.Quality

        init {
            modelMatrix = FloatArray(16)
            localPose = computeLocalPose(rad)
            quality =
               FeatureMapQualityUi.Quality.UNKNOWN
        }

        private fun computeLocalPose(rad: Double): Pose {
            // Rotate around y axis
            val rotation = floatArrayOf(
                0f,
                Math.sin(rad / 2.0).toFloat(),
                0f,
                Math.cos(rad / 2.0).toFloat(),
                0f
            )
            val translation = floatArrayOf(radius, 0f, 0f)
            return Pose.makeRotation(rotation).compose(Pose.makeTranslation(translation))
        }

        fun updateQuality(quality: FeatureMapQuality) {
            if (quality == FeatureMapQuality.INSUFFICIENT) {
                this.quality =
                   FeatureMapQualityUi.Quality.INSUFFICIENT
            } else if (quality == FeatureMapQuality.SUFFICIENT) {
                this.quality =
                  FeatureMapQualityUi.Quality.SUFFICIENT
            } else {
                this.quality =
                  FeatureMapQualityUi.Quality.GOOD
            }
        }

        fun draw(
            uiPose: Pose,
            viewMatrix: FloatArray?,
            projectionMatrix: FloatArray?,
            colorCorrectionRgba: FloatArray?
        ) {
            uiPose.compose(localPose).toMatrix(modelMatrix, 0)
            objectRenderer.updateModelMatrix(modelMatrix, BAR_SCALE)
            if (quality == FeatureMapQualityUi.Quality.UNKNOWN) {
                objectRenderer.draw(
                    viewMatrix, projectionMatrix, colorCorrectionRgba, BAR_COLOR_UNKNOWN_QUALITY
                )
            } else if (quality == FeatureMapQualityUi.Quality.INSUFFICIENT) {
                objectRenderer.draw(
                    viewMatrix, projectionMatrix, colorCorrectionRgba, BAR_COLOR_LOW_QUALITY
                )
            } else if (quality ==FeatureMapQualityUi.Quality.SUFFICIENT) {
                objectRenderer.draw(
                    viewMatrix, projectionMatrix, colorCorrectionRgba, BAR_COLOR_MEDIUM_QUALITY
                )
            } else {
                objectRenderer.draw(
                    viewMatrix, projectionMatrix, colorCorrectionRgba, BAR_COLOR_HIGH_QUALITY
                )
            }
        }
    }

    init {
        radius = MAPPING_UI_RADIUS
        this.objectRenderer = objectRenderer
        arcStartRad = 0.0
        arcEndRad = Math.PI
        numBars = Math.round(Math.PI / MAPPING_UI_SPACING_RADIANS).toInt()
        bars = arrayOfNulls(numBars)
        for (i in 0 until numBars) {
            bars[i] = QualityBar(Math.PI / numBars.toDouble() * i)
        }
    }

    val uiTransform: Pose
        get() = if (isHorizontal) {
            HORIZONTAL_UI_TRANSFORM
        } else {
            VERTICAL_UI_TRANSFORM
        }

    // Average quality value computed over all bars (0.0 for INSUFFICIENT, 0.6 for
    // SUFFICIENT, and 1.0 for GOOD)
    fun computeOverallQuality(): Float {
        var sumQuality = 0f
        for (bar in bars) {
            if (bar!!.quality == FeatureMapQualityUi.Quality.SUFFICIENT) {
                sumQuality += 0.6f
            } else if (bar.quality == FeatureMapQualityUi.Quality.GOOD) {
                sumQuality += 1.0f
            }
        }
        return sumQuality / numBars
    }

    fun updateQualityForViewpoint(cameraPosition: FloatArray, quality: FeatureMapQuality) {
        val idx = computeBarIndex(cameraPosition)
        if (idx >= 0 && idx < numBars) {
            val barInView = bars[idx]
            barInView!!.updateQuality(quality)
        }
    }

    fun drawUi(
        anchorPose: Pose,
        viewMatrix: FloatArray?,
        projectionMatrix: FloatArray?,
        colorCorrectionRgba: FloatArray?
    ) {
        featureMapQualityUIPose = if (isHorizontal) {
            anchorPose.compose(HORIZONTAL_UI_TRANSFORM)
        } else {
            anchorPose.compose(VERTICAL_UI_TRANSFORM)
        }
        for (bar in bars) {
            bar!!.draw(featureMapQualityUIPose!!, viewMatrix, projectionMatrix, colorCorrectionRgba)
        }
    }

    companion object {
        private val TAG = FeatureMapQualityUi::class.java.simpleName
        private val ROTATION_QUATERNION_180_Y = floatArrayOf(
            0f, Math.sin(Math.PI / 2.0).toFloat(), 0f, Math.cos(
                Math.PI / 2.0
            ).toFloat()
        )
        private val ROTATION_QUATERNION_90_Y = floatArrayOf(
            0f,
            Math.sin(-Math.PI / 4.0).toFloat(),
            0f,
            Math.cos(-Math.PI / 4.0).toFloat()
        )
        private val ROTATION_QUATERNION_90_X = floatArrayOf(
            Math.sin(Math.PI / 4.0).toFloat(), 0f, 0f, Math.cos(
                Math.PI / 4.0
            ).toFloat()
        )

        // For anchors on horizontal planes. The UI coordinate frame is rotated 180 degrees with respect
        // to the anchor frame to simplify calculations. The positive x-axis points to the left of the
        // screen and the positive z-axis points away from the camera.
        private val HORIZONTAL_UI_TRANSFORM = Pose.makeRotation(ROTATION_QUATERNION_180_Y)

        // For anchors on vertical planes. The UI coordinate frame rotated with respect to the anchor
        // to face outwards toward the user such that the positive x-axis points to the left of the screen
        // and the positive z-axis points away from the camera into the plane.
        private val VERTICAL_UI_TRANSFORM = Pose.makeRotation(ROTATION_QUATERNION_90_Y)
            .compose(Pose.makeRotation(ROTATION_QUATERNION_90_X))

        // Spacing between indicator bars.
        private val MAPPING_UI_SPACING_RADIANS = Math.toRadians(7.5)
        private const val MAPPING_UI_RADIUS = 0.2f
        private const val BAR_SCALE = 0.3f
        private val BAR_COLOR_UNKNOWN_QUALITY = floatArrayOf(218.0f, 220.0f, 240.0f, 255.0f)
        private val BAR_COLOR_LOW_QUALITY = floatArrayOf(234.0f, 67.0f, 53.0f, 255.0f)
        private val BAR_COLOR_MEDIUM_QUALITY = floatArrayOf(250.0f, 187.0f, 5.0f, 255.0f)
        private val BAR_COLOR_HIGH_QUALITY = floatArrayOf(52.0f, 168.0f, 82.0f, 255.0f)

        /**
         * Returns true if the anchor (specified by anchorTranslationWorld is visible in the camera view (
         * specified by viewMatrix and projectionMatrix); otherwise false.
         */
        fun isAnchorInView(
            anchorTranslationWorld: FloatArray?,
            viewMatrix: FloatArray?,
            projectionMatrix: FloatArray?
        ): Boolean {
            val viewProjectionMatrix = FloatArray(16)
            val anchorTranslationNDC = FloatArray(4)
            // Project point to Normalized Device Coordinates and check if in bounds.
            Matrix.multiplyMM(viewProjectionMatrix, 0, projectionMatrix, 0, viewMatrix, 0)
            Matrix.multiplyMV(
                anchorTranslationNDC,
                0,
                viewProjectionMatrix,
                0,
                anchorTranslationWorld,
                0
            )
            val ndcX = anchorTranslationNDC[0] / anchorTranslationNDC[3]
            val ndcY = anchorTranslationNDC[1] / anchorTranslationNDC[3]
            return !(ndcX < -1 || ndcX > 1 || ndcY < -1 || ndcY > 1)
        }

        fun createHorizontalFeatureMapQualityUi(
            objectRenderer: ObjectRenderer
        ): FeatureMapQualityUi {
            return FeatureMapQualityUi(true, objectRenderer)
        }

        fun createVerticalFeatureMapQualityUi(
            objectRenderer: ObjectRenderer
        ): FeatureMapQualityUi {
            return FeatureMapQualityUi(false, objectRenderer)
        }

        private fun computeBarIndex(viewRay: FloatArray): Int {
            // positive indices.
            val rad = -Math.atan2(viewRay[2].toDouble(), viewRay[0].toDouble())
            return Math.floor(rad / MAPPING_UI_SPACING_RADIANS).toInt()
        }
    }
}