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
 * limitations under the License.
 */
package com.reactnativearviewer

import com.google.ar.core.Anchor
import com.google.ar.core.Anchor.CloudAnchorState
import com.google.ar.core.HostCloudAnchorFuture
import com.google.ar.core.ResolveCloudAnchorFuture
import com.google.ar.core.Session
import com.google.common.base.Preconditions


/**
 * A helper class to handle all the Cloud Anchors logic, and add a callback-like mechanism on top of
 * the existing ARCore API.
 */
internal class CloudAnchorManager(session: Session?) {
    /** Listener for the results of a host operation.  */
    internal interface CloudAnchorHostListener {
        /** This method is invoked when the results of a Cloud Anchor operation are available.  */
        fun onComplete(cloudAnchorId: String?, cloudAnchorState: CloudAnchorState?)
    }

    /** Listener for the results of a resolve operation.  */
    internal interface CloudAnchorResolveListener {
        /** This method is invoked when the results of a Cloud Anchor operation are available.  */
        fun onComplete(cloudAnchorId: String?, anchor: Anchor?, cloudAnchorState: CloudAnchorState?)
    }

    private val session: Session

    init {
        this.session = Preconditions.checkNotNull<Session>(session!!)
    }

    /** Hosts an anchor. The `listener` will be invoked when the results are available.  */
    @Synchronized
    fun hostCloudAnchor(anchor: Anchor?, listener: CloudAnchorHostListener) {
     Preconditions.checkNotNull(listener, "The listener cannot be null.")
        // Creating a Cloud Anchor with lifetime  = 1 day. This is configurable up to 365 days.
        val unused: HostCloudAnchorFuture = session.hostCloudAnchorAsync(
            anchor,  /* ttlDays= */
            1
        ) { cloudAnchorId: String?, cloudAnchorState: CloudAnchorState? ->
            listener.onComplete(
                cloudAnchorId,
                cloudAnchorState
            )
        }
    }

    /** Resolves an anchor. The `listener` will be invoked when the results are available.  */
    @Synchronized
    fun resolveCloudAnchor(anchorId: String?, listener: CloudAnchorResolveListener) {
    Preconditions.checkNotNull(listener, "The listener cannot be null.")
        val unused: ResolveCloudAnchorFuture = session.resolveCloudAnchorAsync(
            anchorId
        ) { anchor, cloudAnchorState -> listener.onComplete(anchorId, anchor, cloudAnchorState) }
    }
}