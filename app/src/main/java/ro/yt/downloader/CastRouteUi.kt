package ro.yt.downloader

import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.mediarouter.app.MediaRouteButton
import com.google.android.gms.cast.framework.CastButtonFactory

object CastRouteUi {

    fun setUpMediaRouteButton(activity: AppCompatActivity, button: MediaRouteButton) {
        CastButtonFactory.setUpMediaRouteButton(activity, button)
        val d = AppCompatResources.getDrawable(activity, R.drawable.ic_action_cast)?.mutate()
        if (d != null) {
            button.setRemoteIndicatorDrawable(d)
        }
    }
}
