/*
 * Patched by: _sajiddz  (Discord: 6aq4)
 * Fork of pizzaschleppa/ReVancedXposed_Spotify
 * Changes: REMOVED_HOME_SECTIONS updated to casita.v1.resolved.Section (Spotify 9.1.90+)
 */
package app.revanced.extension.spotify.misc;

import static java.lang.Boolean.FALSE;
import static java.lang.Boolean.TRUE;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import android.util.Log;
import app.revanced.extension.shared.Logger;
import io.github.chsbuffer.revancedxposed.XposedHelpers;

@SuppressWarnings("unused")
public final class UnlockPremiumPatch {

    /**
     * @param key           Account attribute key.
     * @param overrideValue Override value.
     * @param isExpected    If this attribute is expected to be present in all situations.
     *                      If false, then no error is raised if the attribute is missing.
     */
    private record OverrideAttribute(String key, Object overrideValue, boolean isExpected) {
        OverrideAttribute(String key, Object overrideValue) {
            this(key, overrideValue, true);
        }

        private OverrideAttribute(String key, Object overrideValue, boolean isExpected) {
            this.key = Objects.requireNonNull(key);
            this.overrideValue = Objects.requireNonNull(overrideValue);
            this.isExpected = isExpected;
        }
    }

    private static final List<OverrideAttribute> PREMIUM_OVERRIDES = List.of(
            new OverrideAttribute("ads", FALSE),
            new OverrideAttribute("shuffle", FALSE),
            new OverrideAttribute("on-demand", TRUE),
            new OverrideAttribute("streaming", TRUE),
            new OverrideAttribute("pick-and-shuffle", FALSE),
            new OverrideAttribute("streaming-rules", ""),
            new OverrideAttribute("nft-disabled", "1"),
            new OverrideAttribute("can_use_superbird", TRUE, false),
            new OverrideAttribute("tablet-free", FALSE, false)
    );

    /**
     * A list of home sections feature types ids which should be removed. These ids match the ones from the protobuf
     * response which delivers home sections.
     */
    private static final List<Integer> REMOVED_HOME_SECTIONS = List.of(
            com.spotify.casita.v1.resolved.Section.VIDEO_BRAND_AD_FIELD_NUMBER,
            com.spotify.casita.v1.resolved.Section.IMAGE_BRAND_AD_FIELD_NUMBER
    );

    /**
     * A list of browse sections feature types ids which should be removed. These ids match the ones from the protobuf
     * response which delivers browse sections.
     */
    private static final List<Integer> REMOVED_BROWSE_SECTIONS = List.of(
            com.spotify.browsita.v1.resolved.Section.BRAND_ADS_FIELD_NUMBER
    );

    /**
     * Injection point. Override account attributes.
     */
    public static void overrideAttributes(Map<String, ?> attributes) {
        Log.i("ReVancedXposed", "overrideAttributes called, map size=" + attributes.size());
        try {
            for (OverrideAttribute override : PREMIUM_OVERRIDES) {
                var attribute = attributes.get(override.key);

                if (attribute == null) {
                    if (override.isExpected) {
                        Log.w("ReVancedXposed", "Attribute " + override.key + " expected but NOT FOUND");
                    }
                    continue;
                }

                Object overrideValue = override.overrideValue;
                Object originalValue;
                try {
                    originalValue = XposedHelpers.getObjectField(attribute, "value_");
                } catch (Exception ex) {
                    Log.e("ReVancedXposed", "Failed to get value_ from attribute " + override.key +
                            " (class=" + attribute.getClass().getName() + "): " + ex.getMessage());
                    continue;
                }

                if (overrideValue.equals(originalValue)) {
                    continue;
                }

                Log.i("ReVancedXposed", "Overriding " + override.key + ": " + originalValue + " -> " + overrideValue);
                XposedHelpers.setObjectField(attribute, "value_", overrideValue);
                // Fix valueCase_ to match the new type (2=bool, 3=long, 4=string)
                int valueCase;
                if (overrideValue instanceof Boolean) {
                    valueCase = 2;
                } else if (overrideValue instanceof Long) {
                    valueCase = 3;
                } else {
                    valueCase = 4; // String
                }
                XposedHelpers.setIntField(attribute, "valueCase_", valueCase);
            }
            Log.i("ReVancedXposed", "overrideAttributes complete");
        } catch (Exception ex) {
            Log.e("ReVancedXposed", "overrideAttributes failure: " + ex.getMessage(), ex);
        }
    }

    /**
     * Injection point. Remove station data from Google Assistant URI.
     */
    public static String removeStationString(String spotifyUriOrUrl) {
        try {
            Logger.printInfo(() -> "Removing station string from " + spotifyUriOrUrl);
            return spotifyUriOrUrl.replace("spotify:station:", "spotify:");
        } catch (Exception ex) {
            Logger.printException(() -> "removeStationString failure", ex);
            return spotifyUriOrUrl;
        }
    }

    private interface FeatureTypeIdProvider<T> {
        int getFeatureTypeId(T section);
    }

    private static <T> void removeSections(
            List<T> sections,
            FeatureTypeIdProvider<T> featureTypeExtractor,
            List<Integer> idsToRemove
    ) {
        try {
            Iterator<T> iterator = sections.iterator();

            while (iterator.hasNext()) {
                T section = iterator.next();
                int featureTypeId = featureTypeExtractor.getFeatureTypeId(section);
                if (idsToRemove.contains(featureTypeId)) {
                    Logger.printInfo(() -> "Removing section with feature type id " + featureTypeId);
                    iterator.remove();
                }
            }
        } catch (Exception ex) {
            Logger.printException(() -> "removeSections failure", ex);
        }
    }

    /**
     * Injection point. Remove ads sections from home.
     * Depends on patching abstract protobuf list ensureIsMutable method.
     */
    public static void removeHomeSections(List<?> sections) {
        Logger.printInfo(() -> "Removing ads section from home");
        removeSections(
                sections,
                section -> XposedHelpers.getIntField(section, "featureTypeCase_"),
                REMOVED_HOME_SECTIONS
        );
    }

    /**
     * Injection point. Remove ads sections from browse.
     * Depends on patching abstract protobuf list ensureIsMutable method.
     */
    public static void removeBrowseSections(List<?> sections) {
        Logger.printInfo(() -> "Removing ads section from browse");
        removeSections(
                sections,
                section -> XposedHelpers.getIntField(section, "sectionTypeCase_"),
                REMOVED_BROWSE_SECTIONS
        );
    }
}