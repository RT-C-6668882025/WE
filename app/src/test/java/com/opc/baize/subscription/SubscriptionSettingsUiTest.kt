package com.opc.baize.subscription

import android.view.View
import android.view.ViewGroup
import android.widget.*
import android.os.Looper
import com.opc.baize.model.SubscriptionSelection
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.android.controller.ActivityController

/** Exercises the real production settings widgets/preferences; no live OAuth credentials or network. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SubscriptionSettingsUiTest {
    private var controller: ActivityController<SubscriptionSettingsActivity>? = null
    private fun all(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { all(view.getChildAt(it)) } else emptyList()
    @Before fun setup() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        context.getSharedPreferences("moling", 0).edit().clear().commit()
        SubscriptionGateway::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        val gateway = SubscriptionGateway.get(context)
        SubscriptionGateway::class.java.getDeclaredField("authorized").apply { isAccessible = true }.set(gateway, true)
        val catalog = ModelCatalog.parse(JSONObject("""{"models":[{"slug":"text-model","display_name":"文字模型","visibility":"list"},{"slug":"image-model","display_name":"图片模型","visibility":"list"}]}"""))
        SubscriptionGateway::class.java.getDeclaredField("catalog").apply { isAccessible = true }.set(gateway, catalog)
    }
    @After fun close() { controller?.pause()?.stop()?.destroy(); controller = null }
    private fun launch(): SubscriptionSettingsActivity {
        controller = Robolectric.buildActivity(SubscriptionSettingsActivity::class.java).setup()
        shadowOf(Looper.getMainLooper()).idle()
        return controller!!.get()
    }
    @Test fun savesIndependentModelsAndRestoresChoicesAfterRecreation() {
        var activity = launch()
        var views = all(activity.window.decorView)
        val pickers = views.filterIsInstance<Spinner>(); assertEquals(2, pickers.size)
        assertTrue(pickers[0].adapter.getItem(1).toString().contains("文字模型"))
        pickers[0].setSelection(1); pickers[1].setSelection(2)
        views.filterIsInstance<CheckBox>().single().isChecked = true
        shadowOf(Looper.getMainLooper()).idle()
        views.filterIsInstance<Button>().single { it.text == "保存选择并启用订阅" }.performClick()
        val saved = SubscriptionSelection.read(activity)
        assertEquals("text-model", saved.textModel); assertEquals("image-model", saved.effectiveImageModel()); assertTrue(saved.directVision)
        assertEquals("subscription", activity.getSharedPreferences("moling", 0).getString("model_provider", ""))
        controller!!.pause().stop().destroy(); controller = null
        activity = launch(); views = all(activity.window.decorView)
        val restored = views.filterIsInstance<Spinner>()
        assertEquals(1, restored[0].selectedItemPosition); assertEquals(2, restored[1].selectedItemPosition)
        assertTrue(views.filterIsInstance<CheckBox>().single().isChecked)
    }
    @Test fun emptySelectionCannotEnableAndImageSameAsTextIsSavedExplicitly() {
        val activity = launch(); val views = all(activity.window.decorView)
        val save = views.filterIsInstance<Button>().single { it.text == "保存选择并启用订阅" }
        save.performClick(); assertEquals("", SubscriptionSelection.read(activity).textModel)
        val pickers = views.filterIsInstance<Spinner>(); pickers[0].setSelection(1); pickers[1].setSelection(0)
        shadowOf(Looper.getMainLooper()).idle(); save.performClick()
        assertEquals("text-model", SubscriptionSelection.read(activity).effectiveImageModel())
        assertFalse(views.filterIsInstance<Button>().single { it.text == "取消正在进行的授权" }.visibility == View.VISIBLE)
    }
}
