/**
 * This file is part of Breezy Weather.
 *
 * Breezy Weather is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published by the
 * Free Software Foundation, version 3 of the License.
 *
 * Breezy Weather is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Breezy Weather. If not, see <https://www.gnu.org/licenses/>.
 */

package org.breezyweather.sources.accu

import android.content.Context
import android.graphics.Color
import dagger.hilt.android.qualifiers.ApplicationContext
import io.reactivex.rxjava3.core.Observable
import org.breezyweather.BuildConfig
import org.breezyweather.R
import org.breezyweather.common.basic.models.Location
import org.breezyweather.common.basic.models.options.unit.PrecipitationUnit
import org.breezyweather.common.basic.wrappers.SecondaryWeatherWrapper
import org.breezyweather.common.basic.wrappers.WeatherWrapper
import org.breezyweather.common.exceptions.ApiKeyMissingException
import org.breezyweather.common.exceptions.InvalidLocationException
import org.breezyweather.common.exceptions.SecondaryWeatherException
import org.breezyweather.common.extensions.toCalendarWithTimeZone
import org.breezyweather.common.preference.EditTextPreference
import org.breezyweather.common.preference.ListPreference
import org.breezyweather.common.preference.Preference
import org.breezyweather.common.source.ConfigurableSource
import org.breezyweather.common.source.HttpSource
import org.breezyweather.common.source.LocationSearchSource
import org.breezyweather.common.source.MainWeatherSource
import org.breezyweather.common.source.ReverseGeocodingSource
import org.breezyweather.common.source.SecondaryWeatherSource
import org.breezyweather.common.source.SecondaryWeatherSourceFeature
import org.breezyweather.settings.SettingsManager
import org.breezyweather.settings.SourceConfigStore
import org.breezyweather.sources.accu.json.AccuAirQualityResult
import org.breezyweather.sources.accu.json.AccuAlertResult
import org.breezyweather.sources.accu.json.AccuClimoSummaryResult
import org.breezyweather.sources.accu.json.AccuCurrentResult
import org.breezyweather.sources.accu.json.AccuForecastDailyResult
import org.breezyweather.sources.accu.json.AccuForecastHourlyResult
import org.breezyweather.sources.accu.json.AccuMinutelyResult
import org.breezyweather.sources.accu.preferences.AccuDaysPreference
import org.breezyweather.sources.accu.preferences.AccuHoursPreference
import org.breezyweather.sources.accu.preferences.AccuPortalPreference
import retrofit2.Retrofit
import java.util.Calendar
import java.util.Date
import javax.inject.Inject

class AccuService @Inject constructor(
    @ApplicationContext context: Context,
    client: Retrofit.Builder
) : HttpSource(), MainWeatherSource, SecondaryWeatherSource,
    LocationSearchSource, ReverseGeocodingSource, ConfigurableSource {

    override val id = "accu"
    override val name = "AccuWeather"
    override val privacyPolicyUrl = "https://www.accuweather.com/en/privacy"

    override val color = Color.rgb(240, 85, 20)
    override val weatherAttribution = "AccuWeather"
    override val locationSearchAttribution = weatherAttribution

    private val mDeveloperApi by lazy {
        client
            .baseUrl(ACCU_DEVELOPER_BASE_URL)
            .build()
            .create(AccuDeveloperApi::class.java)
    }
    private val mEnterpriseApi by lazy {
        client
            .baseUrl(ACCU_ENTERPRISE_BASE_URL)
            .build()
            .create(AccuEnterpriseApi::class.java)
    }

    override val supportedFeaturesInMain = listOf(
        SecondaryWeatherSourceFeature.FEATURE_AIR_QUALITY,
        SecondaryWeatherSourceFeature.FEATURE_POLLEN,
        SecondaryWeatherSourceFeature.FEATURE_MINUTELY,
        SecondaryWeatherSourceFeature.FEATURE_ALERT,
        SecondaryWeatherSourceFeature.FEATURE_NORMALS
    )

    override fun requestWeather(
        context: Context, location: Location,
        ignoreFeatures: List<SecondaryWeatherSourceFeature>
    ): Observable<WeatherWrapper> {
        if (!isConfigured) {
            return Observable.error(ApiKeyMissingException())
        }
        if (location.cityId.isNullOrEmpty()) {
            return Observable.error(InvalidLocationException())
        }

        val apiKey = getApiKeyOrDefault()
        val mApi = if (portal == AccuPortalPreference.ENTERPRISE) mEnterpriseApi else mDeveloperApi

        val languageCode = SettingsManager.getInstance(context).language.code
        val metric = SettingsManager.getInstance(context).precipitationUnit != PrecipitationUnit.IN
        val current = mApi.getCurrent(
            location.cityId,
            apiKey,
            languageCode,
            details = true
        )
        val daily = mApi.getDaily(
            days.id,
            location.cityId,
            apiKey,
            languageCode,
            details = true,
            metric = metric
        )
        val hourly = mApi.getHourly(
            hours.id,
            location.cityId,
            apiKey,
            languageCode,
            details = true,
            metric = metric
        )
        val minute = if (!ignoreFeatures.contains(SecondaryWeatherSourceFeature.FEATURE_MINUTELY)
            && mApi is AccuEnterpriseApi
        ) {
            mApi.getMinutely(
                apiKey,
                location.latitude.toString() + "," + location.longitude,
                languageCode,
                details = true
            ).onErrorResumeNext {
                Observable.create { emitter ->
                    emitter.onNext(AccuMinutelyResult())
                }
            }
        } else {
            Observable.create { emitter ->
                emitter.onNext(AccuMinutelyResult())
            }
        }
        val alert = if (!ignoreFeatures.contains(SecondaryWeatherSourceFeature.FEATURE_ALERT)) {
            if (mApi is AccuEnterpriseApi) {
                mApi.getAlertsByPosition(
                    apiKey,
                    location.latitude.toString() + "," + location.longitude,
                    languageCode,
                    details = true
                ).onErrorResumeNext {
                    Observable.create { emitter ->
                        emitter.onNext(ArrayList())
                    }
                }
            } else {
                mApi.getAlertsByCityKey(
                    location.cityId,
                    apiKey,
                    languageCode,
                    details = true
                ).onErrorResumeNext {
                    Observable.create { emitter ->
                        emitter.onNext(ArrayList())
                    }
                }
            }
        } else {
            Observable.create { emitter ->
                emitter.onNext(ArrayList())
            }
        }
        val airQuality = if (!ignoreFeatures.contains(SecondaryWeatherSourceFeature.FEATURE_AIR_QUALITY)
            && mApi is AccuEnterpriseApi) {
            mApi.getAirQuality(
                location.cityId,
                apiKey,
                pollutants = true,
                languageCode
            ).onErrorResumeNext {
                Observable.create { emitter ->
                    emitter.onNext(AccuAirQualityResult())
                }
            }
        } else {
            Observable.create { emitter ->
                emitter.onNext(AccuAirQualityResult())
            }
        }
        // TODO: Only call once a month, unless it’s current position
        val cal = Date().toCalendarWithTimeZone(location.timeZone)
        val climoSummary = if (!ignoreFeatures.contains(SecondaryWeatherSourceFeature.FEATURE_NORMALS)
            && mApi is AccuEnterpriseApi) {
            mApi.getClimoSummary(
                cal[Calendar.YEAR],
                cal[Calendar.MONTH],
                location.cityId,
                apiKey,
                languageCode,
                details = false
            ).onErrorResumeNext {
                Observable.create { emitter ->
                    emitter.onNext(AccuClimoSummaryResult())
                }
            }
        } else {
            Observable.create { emitter ->
                emitter.onNext(AccuClimoSummaryResult())
            }
        }
        return Observable.zip(
            current,
            daily,
            hourly,
            minute,
            alert,
            airQuality,
            climoSummary
        ) { accuRealtimeResults: List<AccuCurrentResult>,
            accuDailyResult: AccuForecastDailyResult,
            accuHourlyResults: List<AccuForecastHourlyResult>,
            accuMinutelyResult: AccuMinutelyResult,
            accuAlertResults: List<AccuAlertResult>,
            accuAirQualityResult: AccuAirQualityResult,
            accuClimoResult: AccuClimoSummaryResult
            ->
            convert(
                location,
                accuRealtimeResults[0],
                accuDailyResult,
                accuHourlyResults,
                accuMinutelyResult,
                accuAlertResults,
                accuAirQualityResult,
                accuClimoResult,
                cal[Calendar.MONTH]
            )
        }
    }

    // SECONDARY WEATHER SOURCE
    override val supportedFeatures = listOf(
        SecondaryWeatherSourceFeature.FEATURE_MINUTELY,
        SecondaryWeatherSourceFeature.FEATURE_ALERT
    )
    override fun isFeatureSupportedForLocation(
        feature: SecondaryWeatherSourceFeature, location: Location
    ): Boolean {
        return (isConfigured && portal == AccuPortalPreference.ENTERPRISE)
    }
    override val airQualityAttribution = null // Only supported by city key
    override val pollenAttribution = null // Only supported by city key
    override val minutelyAttribution = weatherAttribution
    override val alertAttribution = weatherAttribution
    override val normalsAttribution = null // Only supported by city key

    override fun requestSecondaryWeather(
        context: Context, location: Location,
        requestedFeatures: List<SecondaryWeatherSourceFeature>
    ): Observable<SecondaryWeatherWrapper> {
        if (!isConfigured) {
            return Observable.error(ApiKeyMissingException())
        }
        if (portal != AccuPortalPreference.ENTERPRISE) {
            return Observable.error(SecondaryWeatherException())
        }

        val apiKey = getApiKeyOrDefault()
        val languageCode = SettingsManager.getInstance(context).language.code
        val minute = if (requestedFeatures.contains(SecondaryWeatherSourceFeature.FEATURE_MINUTELY)) {
            mEnterpriseApi.getMinutely(
                apiKey,
                location.latitude.toString() + "," + location.longitude,
                languageCode,
                details = true
            )
        } else {
            Observable.create { emitter ->
                emitter.onNext(AccuMinutelyResult())
            }
        }
        val alert = if (requestedFeatures.contains(SecondaryWeatherSourceFeature.FEATURE_ALERT)) {
            mEnterpriseApi.getAlertsByPosition(
                apiKey,
                location.latitude.toString() + "," + location.longitude,
                languageCode,
                details = true
            )
        } else {
            Observable.create { emitter ->
                emitter.onNext(emptyList())
            }
        }
        return Observable.zip(
            minute,
            alert
        ) { accuMinutelyResult: AccuMinutelyResult,
            accuAlertResults: List<AccuAlertResult>
            ->
            convertSecondary(
                if (requestedFeatures.contains(SecondaryWeatherSourceFeature.FEATURE_MINUTELY)) {
                    accuMinutelyResult
                } else null,
                if (requestedFeatures.contains(SecondaryWeatherSourceFeature.FEATURE_ALERT)) {
                    accuAlertResults
                } else null
            )
        }
    }

    override fun requestLocationSearch(
        context: Context,
        query: String
    ): Observable<List<Location>> {
        if (!isConfigured) {
            return Observable.error(ApiKeyMissingException())
        }
        val apiKey = getApiKeyOrDefault()
        val languageCode = SettingsManager.getInstance(context).language.code
        val mApi = if (portal == AccuPortalPreference.ENTERPRISE) mEnterpriseApi else mDeveloperApi
        return mApi.getWeatherLocation(
            apiKey,
            query,
            languageCode,
            details = false,
            alias = "Always"
        ).map { results ->
            results.map {
                convert(null, it)
            }
        }
    }

    override fun isUsable(location: Location): Boolean {
        return !location.cityId.isNullOrEmpty()
    }

    override fun requestReverseGeocodingLocation(
        context: Context,
        location: Location
    ): Observable<List<Location>> {
        if (!isConfigured) {
            return Observable.error(ApiKeyMissingException())
        }
        val apiKey = getApiKeyOrDefault()
        val languageCode = SettingsManager.getInstance(context).language.code
        val mApi = if (portal == AccuPortalPreference.ENTERPRISE) mEnterpriseApi else mDeveloperApi
        return mApi.getWeatherLocationByGeoPosition(
            apiKey,
            languageCode,
            details = false,
            location.latitude.toString() + "," + location.longitude
        ).map {
            val locationList: MutableList<Location> = ArrayList()
            locationList.add(convert(location, it))
            locationList
        }
    }

    // CONFIG
    private val config = SourceConfigStore(context, id)
    private var apikey: String
        set(value) {
            config.edit().putString("apikey", value).apply()
        }
        get() = config.getString("apikey", null) ?: ""

    private var portal: AccuPortalPreference
        set(value) {
            config.edit().putString("portal", value.id).apply()
        }
        get() = AccuPortalPreference.getInstance(
            config.getString("portal", null) ?: "enterprise"
        )

    private var days: AccuDaysPreference
        set(value) {
            config.edit().putString("days", value.id).apply()
        }
        get() = AccuDaysPreference.getInstance(
            config.getString("days", null) ?: "15"
        )

    private var hours: AccuHoursPreference
        set(value) {
            config.edit().putString("hours", value.id).apply()
        }
        get() = AccuHoursPreference.getInstance(
            config.getString("hours", null) ?: "240"
        )

    private fun getApiKeyOrDefault(): String {
        return apikey.ifEmpty { BuildConfig.ACCU_WEATHER_KEY }
    }

    override val isConfigured
        get() = getApiKeyOrDefault().isNotEmpty()

    override val isRestricted = false

    override fun getPreferences(context: Context): List<Preference> {
        return listOf(
            ListPreference(
                titleId = R.string.settings_weather_source_portal,
                selectedKey = portal.id,
                valueArrayId = R.array.accu_preference_portal_values,
                nameArrayId = R.array.accu_preference_portal,
                onValueChanged = {
                    portal = AccuPortalPreference.getInstance(it)
                },
            ),
            EditTextPreference(
                titleId = R.string.settings_weather_provider_accu_api_key,
                summary = { c, content ->
                    content.ifEmpty {
                        c.getString(R.string.settings_source_default_value)
                    }
                },
                content = apikey,
                onValueChanged = {
                    apikey = it
                }
            ),
            ListPreference(
                titleId = R.string.setting_weather_source_accu_days,
                selectedKey = days.id,
                valueArrayId = R.array.accu_preference_day_values,
                nameArrayId = R.array.accu_preference_days,
                onValueChanged = {
                    days = AccuDaysPreference.getInstance(it)
                },
            ),
            ListPreference(
                titleId = R.string.setting_weather_source_accu_hours,
                selectedKey = hours.id,
                valueArrayId = R.array.accu_preference_hour_values,
                nameArrayId = R.array.accu_preference_hours,
                onValueChanged = {
                    hours = AccuHoursPreference.getInstance(it)
                },
            )
        )
    }

    companion object {
        private const val ACCU_DEVELOPER_BASE_URL = "https://dataservice.accuweather.com/"
        private const val ACCU_ENTERPRISE_BASE_URL = "https://api.accuweather.com/"
    }
}