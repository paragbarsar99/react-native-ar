package com.reactnativearviewer

//import com.google.ar.core.Session;
import IntervalRunner
import android.R
import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AttributeSet
import android.util.Base64
import android.util.Log
import android.view.GestureDetector
import android.view.GestureDetector.SimpleOnGestureListener
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.ViewGroup
import android.view.ViewTreeObserver.OnWindowFocusChangeListener
import android.widget.FrameLayout
import com.facebook.react.bridge.Arguments
import com.facebook.react.modules.core.DeviceEventManagerModule
import com.facebook.react.uimanager.ThemedReactContext
import com.facebook.react.uimanager.events.RCTEventEmitter
import com.google.android.filament.utils.Float3
import com.google.android.material.snackbar.Snackbar
import com.google.ar.core.Anchor
import com.google.ar.core.Anchor.CloudAnchorState
import com.google.ar.core.ArCoreApk
import com.google.ar.core.ArCoreApk.InstallStatus
import com.google.ar.core.Camera
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.Session.FeatureMapQuality
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.UnavailableException
import com.google.ar.sceneform.AnchorNode
import com.google.ar.sceneform.ArSceneView
import com.google.ar.sceneform.FrameTime
import com.google.ar.sceneform.HitTestResult
import com.google.ar.sceneform.Node
import com.google.ar.sceneform.Scene
import com.google.ar.sceneform.math.Quaternion
import com.google.ar.sceneform.math.Vector3
import com.google.ar.sceneform.rendering.CameraStream
import com.google.ar.sceneform.rendering.ModelRenderable
import com.google.ar.sceneform.rendering.Renderable
import com.google.ar.sceneform.ux.BaseArFragment.OnSessionConfigurationListener
import com.google.ar.sceneform.ux.FootprintSelectionVisualizer
import com.google.ar.sceneform.ux.TransformableNode
import com.google.ar.sceneform.ux.TransformationSystem
import com.gorisse.thomas.sceneform.scene.await
import com.gorisse.thomas.sceneform.scene.destroy
import java.io.ByteArrayOutputStream
import java.util.EnumSet
import java.util.Timer
import java.util.TimerTask
//import java.util.ArrayList
import java.util.concurrent.CompletableFuture
import kotlin.collections.ArrayList
import kotlin.math.pow
import kotlin.math.sqrt

class ArViewerView
@JvmOverloads
constructor(context: ThemedReactContext, attrs: AttributeSet? = null, defStyleAttr: Int = 0) :
  FrameLayout(context, attrs, defStyleAttr), Scene.OnPeekTouchListener, Scene.OnUpdateListener {
  /** We are going to save anchor points in local store and will redner the object again based to the id we found  */
  private val sharedPreferences = context.getSharedPreferences("anchor", Context.MODE_PRIVATE)

  /** store unresolvedAnchorIds anchor in order to show on scene ui*/
  private var unresolvedAnchorIds: ArrayList<String> = ArrayList()

 /** store resolvedAnchors  in order to show on scene ui*/
  private var resolvedAnchors: ArrayList<Anchor> = ArrayList()

  /** store if we are resolving or hosting an anchor default to hosting*/
  private var currentMode:HostResolveMode = HostResolveMode.HOSTING
/** flag to manage if model attached  to anchor */
    private var isAnchorAttached = false

    private val anchorLock = Any()

    /** We show only one model, let's store the ref here */
  private var modelNode: CustomTransformableNode? = null

    /** We show only one model, let's store the ref here Renderable modal*/
    private var model: Renderable?= null

    /**   anchor to hold hosted anchor  */
    private var anchor: Anchor? = null

    /** Our main view that integrates with ARCore and renders a scene */
  private var arView: ArSceneView? = null

  /** Event listener that triggers on focus */
  private val onFocusListener = OnWindowFocusChangeListener { onWindowFocusChanged(it) }

  /** Event listener that triggers when the ARCore Session is to be configured */
  private val onSessionConfigurationListener: OnSessionConfigurationListener? = null

  /** saving current activity to show snakbar */
  private val currentActivity = context.currentActivity!!

  /** Main view state */
  private var isStarted = false

  /** ARCore installation requirement state */
  private var installRequested = false

  /** Failed session initialization state */
  private var sessionInitializationFailed = false

  /** Depth management enabled state */
  private var isDepthManagementEnabled = false

  /** Light estimation enabled state */
  private var isLightEstimationEnabled = false

  /** Instant placement enabled state */
  private var isInstantPlacementEnabled = true

  /** Plane orientation mode */
  private var planeOrientationMode: String = "both"

  /** Instructions enabled state */
  private var isInstructionsEnabled = true

  /** Device supported state */
  private var isDeviceSupported = true

  /** Reminder to keep track of model loading state */
  private var isLoading = false

  /** Config of the main session initialization */
  private var sessionConfig: Config? = null

  /** AR session initialization */
  private var arSession: Session? = null

  /** Instructions controller initialization */
  private var instructionsController: InstructionsController? = null

  /** Transformation system initialization */
  private var transformationSystem: TransformationSystem? = null

  /** Gesture detector initialization */
  private var gestureDetector: GestureDetector? = null

  /** Reminder to keep source of model loading */
  private var modelSrc: String = ""

  /** Set of allowed model transformations (rotate, scale, translate...) */
  private var allowTransform = mutableSetOf<String>()

  private var cloudAnchorManager: CloudAnchorManager? = null

    private var featureMapQualityUi: FeatureMapQualityUi? = null

    private val featureMapQualityBarObject = ObjectRenderer()

    private var queuedSingleTap: MotionEvent? = null
    private var lastEstimateTimestampMillis: Long = 0


    /** SnakBar instance*/
   enum class HostResolveMode {
    NONE, HOSTING, RESOLVING
  }

  init {
    if (checkIsSupportedDevice(context.currentActivity!!)) {
      // check AR Core installation
      if (requestInstall()) {
        returnErrorEvent("ARCore installation required")
        isDeviceSupported = false
      } else {
        // let's create sceneform view
        arView = ArSceneView(context, attrs)
        arView!!.layoutParams =
          LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        this.addView(arView)

        transformationSystem = makeTransformationSystem()

        gestureDetector =
          GestureDetector(
            context,
            object : SimpleOnGestureListener() {
              override fun onSingleTapUp(e: MotionEvent): Boolean {
                onSingleTap(e)

                return true
              }

              override fun onDown(e: MotionEvent): Boolean {
                return true
              }
            }
          )

        arView!!.scene.addOnPeekTouchListener(this)
        arView!!.scene.addOnUpdateListener(this)
        arView!!.viewTreeObserver.addOnWindowFocusChangeListener(onFocusListener)
        arView!!.setOnSessionConfigChangeListener(this::onSessionConfigChanged)

        val session = Session(context)
        //cloud manager initialize\
        cloudAnchorManager = CloudAnchorManager(session)
        val config = Config(session)

        // Set plane orientation mode
        updatePlaneDetection(config)
        // Enable or not light estimation
        updateLightEstimation(config)
        // Enable or not depth management
        updateDepthManagement(config)

        // Sets the desired focus mode
        config.focusMode = Config.FocusMode.AUTO
        // Force the non-blocking mode for the session.
        config.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
        //Telling Ar core to enabled Cloud Anchor Mode
        config.cloudAnchorMode =  Config.CloudAnchorMode.ENABLED
//        config.cloudAnchorMode = Config.CloudAnchorMode.ENABLED

        sessionConfig = config
        arSession = session
        arView!!.session?.configure(sessionConfig)
        arView!!.session = arSession

        initializeSession()
        resume()

        // Setup the instructions view.
        instructionsController = InstructionsController(context, this)
        instructionsController!!.setEnabled(isInstructionsEnabled)
      }
    } else {
      isDeviceSupported = false
    }
  }

    private fun updateFeatureMapQualityUi(camera: Camera) {
        val now = SystemClock.uptimeMillis()
        // Call estimateFeatureMapQualityForHosting() every 500ms.
        if (now - lastEstimateTimestampMillis > 500) {
            lastEstimateTimestampMillis = now
            // Update the FeatureMapQuality for the current camera viewpoint. Can pass in ANY valid camera
            // pose to estimateFeatureMapQualityForHosting(). Ideally, the pose should represent users’
            // expected perspectives.
            val averageQuality: FeatureMapQuality =
                arSession!!.estimateFeatureMapQualityForHosting(camera.pose)
            onSendHostingEventJs(averageQuality)
            Log.d(
               "HostCloudAnchor",
                "History of average mapping quality calls: $averageQuality"
            )
            if (averageQuality >= FeatureMapQuality.GOOD || averageQuality >= FeatureMapQuality.SUFFICIENT) {
                // Host the anchor automatically if the FeatureMapQuality threshold is reached.
                isAnchorAttached = false   //stop listener
                Log.d(
                    "HostCloudAnchor",
                    "FeatureMapQuality has reached SUFFICIENT-GOOD, triggering hostCloudAnchor()"
                )

                cloudAnchorManager!!.hostCloudAnchor(
                    anchor,
                    HostListener()
                )
            }
        }
        // Render the mapping quality UI.

    }

    //Write in the anchor sharedPref
  private fun onSaveAnchor(key: String, value: String) {
    sharedPreferences.edit().putString(key, value).apply()
  }

  //read from anchor sharedPre
  fun onReadAnchor(key: String): String? {
    return sharedPreferences.getString(key, null) ?: null
  }

  //  show snakbar message
  private fun showSnakBar(message: String) {
//    context.curr
    Snackbar.make(
      currentActivity.findViewById(R.id.content),
      message, Snackbar.LENGTH_LONG
    )
  }

  private fun resume() {
    if (isStarted) {
      return
    }
    if ((context as ThemedReactContext).currentActivity != null) {
      isStarted = true
      try {
        arView!!.resume()
      } catch (ex: java.lang.Exception) {
        sessionInitializationFailed = true
        returnErrorEvent("Could not resume session")
      }
      if (!sessionInitializationFailed) {
        instructionsController?.setVisible(true)
      }
    }
  }


  /**
   * Initializes the ARCore session. The CAMERA permission is checked before checking the
   * installation state of ARCore. Once the permissions and installation are OK, the method
   * #getSessionConfiguration(Session session) is called to get the session configuration to use.
   * Sceneform requires that the ARCore session be updated using LATEST_CAMERA_IMAGE to avoid
   * blocking while drawing. This mode is set on the configuration object returned from the
   * subclass.
   */
  private fun initializeSession() {
    // Only try once
    if (sessionInitializationFailed) {
      return
    }
    // if we have the camera permission, create the session
    if (CameraPermissionHelper.hasCameraPermission((context as ThemedReactContext).currentActivity)
    ) {
      val sessionException: UnavailableException?
      try {
        onSessionConfigurationListener?.onSessionConfiguration(arSession, sessionConfig)

         onSetTimer(::setFrameListener) // hack to run the resolve methode in some delay
          // run a JS event
        Log.d("ARview session", "started")
        val event = Arguments.createMap()
        val reactContext = context as ThemedReactContext
        reactContext.getJSModule(RCTEventEmitter::class.java).receiveEvent(id, "onStarted", event)

        return
      } catch (e: UnavailableException) {
        sessionException = e
      } catch (e: java.lang.Exception) {
        sessionException = UnavailableException()
        sessionException.initCause(e)
      }
      sessionInitializationFailed = true
      returnErrorEvent(sessionException?.message)
    } else {
      returnErrorEvent("Missing camera permissions")
    }
  }

  /** Removed the focus listener */
  fun onDrop() {
    if (arView != null) {
      arView!!.pause()
      arView!!.session?.close()
      arView!!.destroy()
      arView!!.viewTreeObserver.removeOnWindowFocusChangeListener(onFocusListener)
    }
  }

  /** Occurs when a session configuration has changed. */
  private fun onSessionConfigChanged(config: Config) {
    instructionsController?.setEnabled(config.planeFindingMode !== Config.PlaneFindingMode.DISABLED)
  }

  /** Creates the transformation system used by this view. */
  private fun makeTransformationSystem(): TransformationSystem {
    val selectionVisualizer = FootprintSelectionVisualizer()
    return TransformationSystem(resources.displayMetrics, selectionVisualizer)
  }

  /** Makes the transformation system responding to touches */
  override fun onPeekTouch(hitTestResult: HitTestResult, motionEvent: MotionEvent?) {
    transformationSystem!!.onTouch(hitTestResult, motionEvent)
    if (hitTestResult.node == null && motionEvent != null) {
      gestureDetector!!.onTouchEvent(motionEvent)
    }
  }

  /** On each frame */
  override fun onUpdate(frameTime: FrameTime?) {
    if (arView!!.session == null) {
        return
    }
      val frame: Frame = arSession!!.update()
      val camera = frame.camera
    if (instructionsController != null) {
      // Instructions for the Plane finding mode.
      val showPlaneInstructions: Boolean = !arView!!.hasTrackedPlane()
      if (instructionsController?.isVisible() != showPlaneInstructions) {
        instructionsController?.setVisible(showPlaneInstructions)
      }
    }

    if (arView!!.arFrame?.camera?.trackingState == TrackingState.TRACKING &&
         anchor != null &&
         anchor!!.trackingState == camera.trackingState &&
         currentMode == HostResolveMode.HOSTING && isAnchorAttached
    ) {
        // Obtain the current frame from ARSession. When the configuration is set to
        // UpdateMode.BLOCKING (it is by default), this will throttle the rendering to the
        updateFeatureMapQualityUi(camera)

    }else if(currentMode == HostResolveMode.RESOLVING && arView!!.hasTrackedPlane()){
         for(resolvedAnchor in resolvedAnchors){
             if(resolvedAnchor!= null  && resolvedAnchor.trackingState == TrackingState.TRACKING
                 ){
                  measureDistanceFromCamera(resolvedAnchor)
              }

         }
    }
  }

/*
 measure distance between two object in this case our camera and model place in real-world
*/
    private fun calculateEucledeanDistance(x: Float, y: Float, z: Float): Float{
        return sqrt(x.pow(2) + y.pow(2) + z.pow(2))
    }
    private fun calculateDistance(objectPose0: Vector3, objectPose1: Pose): Float{
        return calculateEucledeanDistance(
            objectPose0.x - objectPose1.tx(),
            objectPose0.y - objectPose1.ty(),
            objectPose0.z - objectPose1.tz()
        )
    }

private fun measureDistanceFromCamera(anchor:Anchor){
    val frame = arView?.arFrame
    val node = AnchorNode(anchor)
    if(anchor != null){
        val distanceMeter = calculateDistance(node.worldPosition,frame!!.camera.pose)
//        onSendUpdateDistanceToJS(distanceMeter)

        if(distanceMeter.toInt() == 0){
             anchor.destroy()
            onRemoveModelSendEventToJS()
        }
    }

}



  fun onSingleTap(motionEvent: MotionEvent?) {
      Log.d("CurrentMode",currentMode.toString())
    if (arView != null) {
      val frame: Frame? = arView!!.arFrame
      transformationSystem?.selectNode(null)

      if (frame != null && frame.camera.trackingState === TrackingState.TRACKING) {
            // Create An anchor only in hosting mode otherwise return
          if(currentMode != null && currentMode === HostResolveMode.RESOLVING){ return}
          for (hitResult in frame.hitTest(motionEvent)) {
            val trackable = hitResult.trackable
            if (trackable is Plane &&
              trackable.isPoseInPolygon(hitResult.hitPose) &&
              modelNode != null && !isAnchorAttached
            ) {
                Log.d("Anchor point found", anchor.toString())
                //var modelAlreadyAttached = false
                //only one anchor can host a time
                   val anc: Anchor = arView!!.session!!.createAnchor(hitResult.hitPose)
                   anchor = anc
                   initAnchorNode(anc)
                   //tells JS that the model is visible
                   onModelPlaced()

               }
              break
            }

        }

      }
    }

    private fun onSetTimer(action:() -> Unit){
        val timer = Timer()
        val timeoutMillis = 2000L
//     Schedule a task to be executed after the timeout
        timer.schedule(object : TimerTask() {
            override fun run() {
                action()
            }
        }, timeoutMillis)
    }

    private  fun setFrameListener() {
        if(currentMode == HostResolveMode.RESOLVING){
           IntervalRunner().start(1000,::onPrivacyAcceptedForResolve)
        }

    }

    private fun onSendUpdateDistanceToJS(distance: Float) {
        val event = Arguments.createMap()
        event.putString("onSendUpdateDistanceToJS", "Distance to anchor: $distance meters")
        val reactContext = context as ThemedReactContext
        val eventEmitter = reactContext.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
        eventEmitter.emit("onSendUpdateDistanceToJS",event)
    }


    private fun onPrivacyAcceptedForResolve() {
    try {
        val frame: Frame? = arView!!.arFrame

        Log.d("Arview currentMode",currentMode.toString())
        Log.d("Arview FrameRate",frame.toString())

        if (currentMode == HostResolveMode.RESOLVING && frame != null &&
            frame.camera.trackingState == TrackingState.TRACKING) {
            Log.d(
                "AcceptedForResolve",
                "Attempting to resolve $unresolvedAnchorIds , ${unresolvedAnchorIds.size}",
            )
            IntervalRunner().stop()
            for (cloudAnchorId in unresolvedAnchorIds) {
                cloudAnchorManager!!.resolveCloudAnchor(cloudAnchorId, ResolveListener())
            }
    }
    }catch (e:Exception){
    Log.d("AcceptedForResolveError",e.toString())
       }
    }

  private fun onPlaceModal(anchorNode: AnchorNode?) {
    if ((modelSrc == null || modelSrc == "") && (anchorNode == null || !isDeviceSupported) ) {
        return
    }
              ModelRenderable.builder()
                  .setSource(context, Uri.parse(modelSrc))
                  .setIsFilamentGltf(true)
                  .build()
                  .thenAccept {
                      modelNode = CustomTransformableNode(transformationSystem!!)
                      modelNode!!.select()
                      modelNode!!.renderable = it
                      // set model at center
                      modelNode!!.renderableInstance.filamentAsset.let { asset ->
                          val center = asset!!.boundingBox.center.let { v -> Float3(v[0], v[1], v[2]) }
                          val halfExtent = asset.boundingBox.halfExtent.let { v -> Float3(v[0], v[1], v[2]) }
                          val fCenter = -(center + halfExtent * Float3(0f, -1f, 1f)) * Float3(1f, 1f, 1f)
                          modelNode!!.localPosition = Vector3(fCenter.x, fCenter.y, fCenter.z)
                      }

                      Log.d("ARview model", currentMode.toString())
                      isLoading = false
                      anchorNode?.parent = arView!!.scene
                      modelNode!!.parent = anchorNode
                      onTransformChanged()
                  }
                  .exceptionally {
                      Log.e("ARview model", "cannot load")
                      returnErrorEvent("Cannot load the model: " + it.message)
                      return@exceptionally null
                  }

      }

  private fun initAnchorNode(anchor: Anchor) {
      val anchorNode = AnchorNode(anchor)
       anchorNode.parent = arView!!.scene
       modelNode!!.parent = anchorNode
       isAnchorAttached= true
  }


  private fun onModelPlaced() {
    val event = Arguments.createMap()
    val reactContext = context as ThemedReactContext
    reactContext.getJSModule(RCTEventEmitter::class.java).receiveEvent(id, "onModelPlaced", event)
  }

    private  fun onSendCurrentAnchorResolving(anchorId:String){
        val event = Arguments.createMap()
        event.putString("onSendCurrentAnchorResolving",anchorId.toString())
        val reactContext = context as ThemedReactContext
        val eventEmitter = reactContext.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
        eventEmitter.emit("onSendCurrentAnchorResolving",event)
    }

   // send hosted anchor id to js
    private fun onSendHostAnchorToJs(anchorId:String?) {
       try {
       val event = Arguments.createMap()
     if(anchorId!=null){
       event.putString("HostedAnchorId",anchorId.toString())
       val reactContext = context as ThemedReactContext
         val eventEmitter = reactContext.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
         eventEmitter.emit("HostedAnchorId",event)
      }
       }catch(e:Exception){
        Log.e("onSendHostAnchorToJs",e.toString())
       }
     }
/**
 * hosting event sent to js
 * */
    private fun onSendHostingEventJs(quality:FeatureMapQuality?) {
        try {
            val event = Arguments.createMap()
            if(quality !=null){
                event.putString("HostingEvent",quality.toString())
                val reactContext = context as ThemedReactContext
                val eventEmitter = reactContext.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
                eventEmitter.emit("HostingEvent",event)
            }
        }catch(e:Exception){
            Log.e("onSendHostingEvent",e.toString())
        }
    }

    /** Request ARCore installation */
  private fun requestInstall(): Boolean {
    when (ArCoreApk.getInstance()
      .requestInstall((context as ThemedReactContext).currentActivity, !installRequested)
    ) {
      InstallStatus.INSTALL_REQUESTED -> {
        installRequested = true
        return true
      }

      InstallStatus.INSTALLED -> {}
    }
    return false
  }

  /** Set plane detection orientation */
  fun setPlaneDetection(planeOrientation: String) {
    planeOrientationMode = planeOrientation
    sessionConfig.let {
      updatePlaneDetection(sessionConfig)
      updateConfig()
    }
  }

  private fun updatePlaneDetection(config: Config?) {
    when (planeOrientationMode) {
      "horizontal" -> {
        config?.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL
        if (modelNode != null) {
          modelNode!!.translationController.allowedPlaneTypes.clear()
          modelNode!!.translationController.allowedPlaneTypes.add(
            Plane.Type.HORIZONTAL_DOWNWARD_FACING
          )
          modelNode!!.translationController.allowedPlaneTypes.add(
            Plane.Type.HORIZONTAL_UPWARD_FACING
          )
        }
      }

      "vertical" -> {
        config?.planeFindingMode = Config.PlaneFindingMode.VERTICAL
        if (modelNode != null) {
          modelNode!!.translationController.allowedPlaneTypes.clear()
          modelNode!!.translationController.allowedPlaneTypes.add(Plane.Type.VERTICAL)
        }
      }

      "both" -> {
        config?.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
        if (modelNode != null) {
          modelNode!!.translationController.allowedPlaneTypes.clear()
          modelNode!!.translationController.allowedPlaneTypes =
            EnumSet.allOf(Plane.Type::class.java)
        }
      }

      "none" -> {
        config?.planeFindingMode = Config.PlaneFindingMode.DISABLED
        if (modelNode != null) {
          modelNode!!.translationController.allowedPlaneTypes.clear()
        }
      }
    }
  }

  /** Set whether instant placement is enabled */
  fun setInstantPlacementEnabled(isEnabled: Boolean) {
    isInstantPlacementEnabled = isEnabled
  }

  /** Set whether light estimation is enabled */
  fun setLightEstimationEnabled(isEnabled: Boolean) {
    isLightEstimationEnabled = isEnabled
    sessionConfig.let {
      updateLightEstimation(sessionConfig)
      updateConfig()
    }
  }

  private fun updateLightEstimation(config: Config?) {
    if (!isLightEstimationEnabled) {
      config?.lightEstimationMode = Config.LightEstimationMode.DISABLED
    } else {
      config?.lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
    }
  }

  /** Set whether depth management is enabled */
  fun setDepthManagementEnabled(isEnabled: Boolean) {
    isDepthManagementEnabled = isEnabled
    sessionConfig.let {
      updateDepthManagement(sessionConfig)
      updateConfig()
    }
  }

  private fun updateDepthManagement(config: Config?) {
    if (!isDepthManagementEnabled) {
      sessionConfig?.depthMode = Config.DepthMode.DISABLED
      arView?.cameraStream?.depthOcclusionMode =
        CameraStream.DepthOcclusionMode.DEPTH_OCCLUSION_DISABLED
    } else {
      if (arSession?.isDepthModeSupported(Config.DepthMode.AUTOMATIC) == true) {
        sessionConfig?.depthMode = Config.DepthMode.AUTOMATIC
      }
      arView?.cameraStream?.depthOcclusionMode =
        CameraStream.DepthOcclusionMode.DEPTH_OCCLUSION_ENABLED
    }
  }

  private fun updateConfig() {
    if (isStarted) {
      arSession?.configure(sessionConfig)
    }
  }
  /** set the curretMode */

  @SuppressLint("SuspiciousIndentation")
  fun onSetCurrentModeResolve(anchorId:ArrayList<String>){
      try {
      currentMode = HostResolveMode.RESOLVING
      Log.d("onSetCurrentModeResolve",anchorId.toString())
      unresolvedAnchorIds = anchorId
    Log.d("Available Anchor " ,resolvedAnchors.toString())

      }catch (e:Exception){
        Log.e("onSetCurrentModeResolve",e.toString())
      }
  }

/** Remove event sent to js */
    private fun onRemoveModelSendEventToJS (){
    val event = Arguments.createMap()
    val reactContext = context as ThemedReactContext
    reactContext.getJSModule(RCTEventEmitter::class.java).receiveEvent(
        id,
        "onModelRemoved",
        event
    )
    }
/** Remove attached Model */
private fun onRemoveModel(){
    try {
    if (modelNode?.parent is AnchorNode) {
        Log.d("ARview model", "detaching")
        (modelNode!!.parent as AnchorNode).anchor?.destroy() // free up memory of anchor
        arView?.scene?.removeChild(modelNode)
        anchor = null

    }
    }catch (e:Exception){
    Log.d("Ar Modal Exception" ,e.toString())
    }
    }


  /** Start the loading of a GLB model URI */
  fun loadModel(src: String) {
      if (isDeviceSupported) {
          onRemoveModel() //remove old model
          Log.d("ARview model", "loading")
          modelSrc = src
          isLoading = true

          ModelRenderable.builder()
              .setSource(context, Uri.parse(src))
              .setIsFilamentGltf(true)
              .build()
              .thenAccept {
                  modelNode = CustomTransformableNode(transformationSystem!!)
                  modelNode!!.select()
                  modelNode!!.renderable = it
                  // set model at center
                  modelNode!!.renderableInstance.filamentAsset.let { asset ->
                      val center = asset!!.boundingBox.center.let { v -> Float3(v[0], v[1], v[2]) }
                      val halfExtent = asset.boundingBox.halfExtent.let { v -> Float3(v[0], v[1], v[2]) }
                      val fCenter = -(center + halfExtent * Float3(0f, -1f, 1f)) * Float3(1f, 1f, 1f)
                      modelNode!!.localPosition = Vector3(fCenter.x, fCenter.y, fCenter.z)
                  }

                  Log.d("ARview model", "loaded")
                  isLoading = false

                  // set transforms on model
                  onTransformChanged()
              }
              .exceptionally {
                  Log.e("ARview model", "cannot load")
                  returnErrorEvent("Cannot load the model: " + it.message)
                  return@exceptionally null
              }
      }
  }

  /** Rotate the model with the requested angle */
  fun rotateModel(pitch: Number, yaw: Number, roll: Number) {
    Log.d("ARview rotateModel", "pitch: $pitch deg / yaw: $yaw deg / roll: $roll deg")
    modelNode?.localRotation =
      Quaternion.multiply(
        modelNode?.localRotation,
        Quaternion.eulerAngles(Vector3(pitch.toFloat(), yaw.toFloat(), roll.toFloat()))
      )
  }

  /** Remove the model from the view and reset plane detection */
  fun resetModel() {
    Log.d("ARview model", "Resetting model")
    if (modelNode != null) {
        loadModel(modelSrc)
    }
  }

  /** Add a transformation to the allowed list */
  fun addAllowTransform(transform: String) {
    allowTransform.add(transform)
    onTransformChanged()
  }

  /** Remove a transformation to the allowed list */
  fun removeAllowTransform(transform: String) {
    allowTransform.remove(transform)
    onTransformChanged()
  }

  private fun onTransformChanged() {
    if (modelNode == null) return
    modelNode!!.scaleController.isEnabled = allowTransform.contains("scale")
    modelNode!!.rotationController.isEnabled = allowTransform.contains("rotate")
    modelNode!!.translationController.isEnabled = allowTransform.contains("translate")
  }

  /** Enable/Disable instructions */
  fun setInstructionsEnabled(isEnabled: Boolean) {
    isInstructionsEnabled = isEnabled
    instructionsController?.setEnabled(isInstructionsEnabled)
  }

  /** Takes a screenshot of the view and send it to JS through event */
  fun takeScreenshot(requestId: Int) {
    Log.d("ARview takeScreenshot", requestId.toString())

    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    var encodedImage: String? = null
    var encodedImageError: String? = null
    PixelCopy.request(
      arView!!,
      bitmap,
      { copyResult ->
        if (copyResult == PixelCopy.SUCCESS) {
          try {
            val byteArrayOutputStream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 70, byteArrayOutputStream)
            val byteArray = byteArrayOutputStream.toByteArray()
            val encoded = Base64.encodeToString(byteArray, Base64.DEFAULT)
            encodedImage = encoded
            Log.d("ARview takeScreenshot", "success")
          } catch (e: Exception) {
            encodedImageError = "The image cannot be saved: " + e.localizedMessage
            Log.d("ARview takeScreenshot", "fail")
          }
          returnDataEvent(requestId, encodedImage, encodedImageError)
        }
      },
      Handler(Looper.getMainLooper())
    )
  }

  /** Send back an event to JS */
  private fun returnDataEvent(requestId: Int, result: String?, error: String?) {
    val event = Arguments.createMap()
    event.putString("requestId", requestId.toString())
    event.putString("result", result)
    event.putString("error", error)
    val reactContext = context as ThemedReactContext
    reactContext.getJSModule(RCTEventEmitter::class.java).receiveEvent(id, "onDataReturned", event)
  }

  /** Send back an error event to JS */
  private fun returnErrorEvent(message: String?) {
    val event = Arguments.createMap()
    event.putString("message", message)
    val reactContext = context as ThemedReactContext
    reactContext.getJSModule(RCTEventEmitter::class.java).receiveEvent(id, "onError", event)
  }

  /**
   * Returns false and displays an error message if Sceneform can not run, true if Sceneform can run
   * on this device.
   *
   * Sceneform requires Android N on the device as well as OpenGL 3.0 capabilities.
   *
   * Finishes the activity if Sceneform can not run
   */
  private fun checkIsSupportedDevice(activity: Activity): Boolean {
    val openGlVersionString =
      (activity.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager)
        .deviceConfigurationInfo
        .glEsVersion
    if (openGlVersionString.toDouble() < 3.0) {
      returnErrorEvent("This feature requires OpenGL ES 3.0 later")
      return false
    }
    return true
  }


  /** Adds a new anchor to the set of resolved anchors.  */
  private fun setAnchorAsResolved(cloudAnchorId: String?, newAnchor: Anchor?) {
        if(cloudAnchorId != null && newAnchor != null){
      if (unresolvedAnchorIds.contains(cloudAnchorId)) {
          resolvedAnchors.add(newAnchor)
          if( newAnchor.trackingState == TrackingState.TRACKING ){
              onPlaceModal(AnchorNode(newAnchor))
          }
          onSendCurrentAnchorResolving(cloudAnchorId.toString())
        unresolvedAnchorIds.remove(cloudAnchorId)
      }
    }


  }

    private inner class ResolveListener : CloudAnchorManager.CloudAnchorResolveListener {
        override fun onComplete(
            cloudAnchorId: String?,
            anchor: Anchor?,
            cloudAnchorState: CloudAnchorState?
        ) {
            if (cloudAnchorState != null) {
                if (cloudAnchorState.isError) {
                    Log.d(
                        "OnResolveError",
                        "Error Resolving a cloud anchor, state $cloudAnchorState ${cloudAnchorId.toString()}"
                    )

                    return
                }
            }
            setAnchorAsResolved(cloudAnchorId, anchor)

                if (unresolvedAnchorIds.isEmpty()) {
                    Log.d(
                    "OnResloveComplete",
                    "All Anchor Resolved",
                )
                } else {
                    Log.d(
                        "OnResloveComplete",
                        "Attempting to resolve %d anchor(s): %s ${cloudAnchorId.toString()} ${anchor.toString()}",
                    )
                }

        }
    }


    private inner class HostListener : CloudAnchorManager.CloudAnchorHostListener {

     override fun onComplete(cloudID: String?, cloudAnchorState: CloudAnchorState?) {
       if (cloudAnchorState!!.isError) {
         Log.e(
           "OnHostCompletedError",
           "Error hosting a cloud anchor, state $cloudAnchorState"
         )
         return
       }
         onSendHostAnchorToJs(cloudID)

       Log.d(
         "OnHostCompleted",
         "Anchor $cloudID created."
       )

     }
   }


}


/**
 *  val oldAnchor =  onReadAnchor("anchored")
 *               if(oldAnchor !=  null){
 *                  val isSame = oldAnchor == anchor.toString();
 *                  Log.d("Old match found", isSame.toString())
 *               }else{
 *                 onSaveAnchor("anchored",anchor.toString())
 *                 Log.d("new Anchor created", anchor.toString())
 *
 *               }
 * */