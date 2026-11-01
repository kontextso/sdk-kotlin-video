# Test parity with the Swift SDK

Every test of sdk-swift-video's suite (517) mapped to the Kotlin suite: COVERED by an existing Kotlin test,
NEW (a Kotlin test written for it, each checked to fail when the rule it guards is broken), FIXED (the rule did
not hold in Kotlin; the source was fixed and the test kept), or N/A (iOS only, with the reason).

| Area | Swift tests | Covered | New | Fixed | N/A |
|---|---|---|---|---|---|
| Payloads, requests, models | 94 | 26 | 50 | 2 | 16 |
| Ad lifecycle: show, placement, ending, linear screen | 129 | 36 | 65 | 2 | 26 |
| VideoAds, loading, initialization | 110 | 33 | 69 | 0 | 8 |
| Content following, frame bridge, viewability, clock | 110 | 37 | 67 | 0 | 6 |
| Tracker, API client, reporting, logging | 74 | 22 | 41 | 3 | 8 |
| **Total** | **517** | **154** | **292** | **7** | **64** |

The N/A tests are mostly SKAdNetwork, App Tracking Transparency and UIKit presentation (31 of them SKAdNetwork alone).

How far a COVERED row goes: the lifecycle and VideoAds suites mostly inject the page's messages into the bridge
(`bridge.receive`), so they cover the SDK's state machine, not the transport; Swift's integration tests run live
page JavaScript. The transport is covered once, end to end, by the emulator test `FrameProtocolTest` (the page's
own `KontextAndroid.postMessage`, `ad.show` back to it, the impression it triggers). Rows marked "weaker" or
"partly" say what they leave out.

The fixes: a `scalable` size flag of the wrong type is refused; GPP section ids are read when written with commas or
as a number; a POST is never resent by the HTTP stack after a dropped connection (one attempt stays one); the log
drops a URL's port; debug numbers beyond a Long print as themselves; huge insets saturate instead of wrapping; a linear ad interrupted while its screen is up closes that screen. Redirects are followed as Swift's URLSession follows them (307 and
308 resend the POST, 301 to 303 continue as a GET, at most five, never https to http), explicitly, since the
streamed body turned off the HTTP stack's own following: `ApiClientRedirectTest`.


## Parity: payloads, request bodies, models, public types

Kotlin new tests live in `video/src/test/kotlin/so/kontext/video/internal/ParityPayloadsTest.kt`, in two classes:
`ParityPayloadsTest` (JUnit 5, pure) and `ParityRequestsTest` (Robolectric, extends `SdkTest`).
Every mutation was applied to the real source; the named test failed; the source was restored.

## DecodingTests.swift (52)

| Swift test | Status | Kotlin test | Note |
|---|---|---|---|
| configEnabledWithReportDebug | COVERED | PayloadsTest.configDefaultsTheReportsToOff | `{"enabled":true,"reportDebug":true,...}` -> Enabled(true, true) |
| configEnabledDefaultsReportDebugToFalse | COVERED | PayloadsTest.configDefaultsTheReportsToOff | `{"enabled":true}` -> Enabled(false, false) |
| configDisabledCarriesTheReason | COVERED | PayloadsTest.configDefaultsTheReportsToOff | Disabled("x") |
| configDisabledWithoutReasonIsUnknown | NEW | ParityPayloadsTest.aConfigWithoutAReasonIsDisabledForAnUnknownOne | mutation: ConfigResponse default reason `UNKNOWN_REASON` -> `"none"` |
| adsResponseWithAdIgnoresTimedModeFields | NEW | ParityPayloadsTest.theTimedModeFieldsAreIgnoredOnAFilledAndANoFillEntry | mutation: AdsResponse reads `noFillReason` on a filled entry too |
| adsResponseAnswersPerFormat | COVERED | PayloadsTest.aResponseGivesEachFormatItsAdOrItsNoFillReason | overlay ad, pause frequencyCap, linear Other |
| adsResponseNoFillIgnoresWhatItDoesNotRead | NEW | ParityPayloadsTest.theTimedModeFieldsAreIgnoredOnAFilledAndANoFillEntry | no-fill entry with `nextRequestAt` reads temporaryError; same mutation |
| adsResponseNoFillDefaults | COVERED | PayloadsTest.aNoFillWithoutAReasonIsUnknown | |
| adsResponseNoFillReasonsTheSDKDoesNotKnowPassThrough | NEW | ParityPayloadsTest.anUnknownNoFillReasonKeepsItsWireNameForLogs | Other equality was covered; the wire name in `toString` was not. mutation: `Other` passes `"other"` as its wire name |
| aSizeMustBePositive | COVERED | PayloadsTest.nonsenseIsRefused | width 0 refused |
| adsResponseReasonsOfTheWrongTypeReadUnknown | NEW | ParityPayloadsTest.reasonsOfTheWrongTypeReadUnknown | mutation: AdsResponse disabledReason read as any element's text |
| adsResponseWithoutFormatsOrWithAnUnknownFormatIsRejected | NEW | ParityPayloadsTest.anAdsAnswerWithoutEnabledOrFormatsOrWithAnUnknownFormatOrNoAdsIsRefused | missing `ads` was covered by RacesTest.aMalformedAnswerIsARequestError. mutation: missing `formats` -> empty list |
| aResponseForOtherFormatsHasNoEntryForThisOne | COVERED | PayloadsTest.aNoFillWithoutAReasonIsUnknown | `result(OVERLAY)` null |
| adsResponseDisabled | COVERED | PayloadsTest.theKillSwitchCarriesItsReason | |
| adsResponseRequiresEnabled | NEW | ParityPayloadsTest.anAdsAnswerWithoutEnabledOrFormatsOrWithAnUnknownFormatOrNoAdsIsRefused | same test; `{"formats": []}` refused |
| anAppInstallAdCarriesItsSKAdNetworkPayload | N/A | | SKAdNetwork / AdAttributionKit are iOS-only |
| anAdWithoutAttributionHasNone | N/A | | iOS attribution |
| numericSKAdNetworkIdsAreTakenAsStrings | N/A | | iOS attribution |
| aMalformedSKAdNetworkPayloadDropsTheAttributionNotTheAd | NEW (Android part) | ParityPayloadsTest.fieldsTheSdkNeverReadsDoNotFailTheAd | SKAN parsing is iOS-only; the Android rule kept is "an attribution object, even junk, never fails the ad". mutation: AdPayload refuses `placementId` (same test, the other extras) |
| onlyFidelitiesZeroAndOneCount | N/A | | iOS attribution |
| anEntryThatDoesNotDecodeIsDroppedAndTheRestKept | N/A | | iOS attribution |
| badEntriesBeforeAndBetweenGoodOnesAreSkipped | N/A | | iOS attribution |
| theSameFidelityTwiceDropsThePayload | N/A | | iOS attribution |
| anEmptyRequiredStringIsMissing | N/A | | iOS attribution |
| aMalformedSKANNextToAValidAAKKeepsTheJWS | N/A | | iOS attribution |
| anAdOfAnotherFormatThanItsResultFailsTheResponse | COVERED | PayloadsTest.aFormatListedTwiceOrAnAdUnderTheWrongFormatIsRefused | overlay ad under pause refused; under overlay accepted in aResponseGivesEachFormat... |
| aFormatListedTwiceFailsTheResponse | COVERED | PayloadsTest.aFormatListedTwiceOrAnAdUnderTheWrongFormatIsRefused | |
| clearlyBrokenAdsAreRefused | NEW | PayloadsTest.nonsenseIsRefused + GapsTest.thePayloadsLastRefusals; new: ParityPayloadsTest.anExpiryBelowZeroIsRefusedAndAZeroOverlayDurationIsNot, aLinearAdIsAVideoAndWithoutSkipOffsetHasNoCloseBeforeTheEnd | empty id, expires 0, duration -1, skipOffset -1 were covered; expires -5 and skipOffset 0 accepted were not. mutation: `expires <= 0` -> `expires == 0L` |
| anAbsurdSizeIsRefused | NEW | ParityPayloadsTest.aSideAboveTenThousandIsRefusedAndTenThousandIsNot | 20000 was covered, not the 10000/10001 boundary nor Long.MAX. mutation: range `1..MAX_SIDE + 1` |
| aSizeSaysWhetherItMayShrink | COVERED | PayloadsTest.anOverlayDecodesWithEveryFact (absent -> scalable), EdgesTest.theBiggestVariantThatFitsUnscaledWinsElseTheOneShrunkLeast (`scalable: false` decoded and obeyed); ParityPayloadsTest.aPauseAdDecodesWithEveryFact asserts it directly | |
| theFrameURLMustBeAbsoluteWithAHost | NEW | ParityPayloadsTest.theFrameUrlMustBeAbsoluteWithAHostAndIsKeptAsGiven | three were covered; full list, accepted URLs kept as given. mutation: scheme check without `lowercase()` |
| pauseAdWithSize | NEW | ParityPayloadsTest.aPauseAdDecodesWithEveryFact | creative, expires, frame URL, position, sizes. mutation: `"image"` -> `Creative.HTML`. Trigger is not stored (timed mode is not in the SDK) |
| fullscreenPauseAdHasNoSizes | COVERED | PayloadsTest.aFullScreenPauseAdHasNoSizes | |
| anEmptySizesListIsRejected | NEW | PayloadsTest.nonsenseIsRefused (empty list); new: ParityPayloadsTest.aPauseAdAtTheTopNeedsItsSizes | missing `sizes` was not covered. mutation: missing sizes -> `listOf(Size(1, 1))` |
| overlayAdWithWindowTrigger | COVERED | GapsTest.thePayloadsLastRefusals (window decodes) + PayloadsTest.anOverlayDecodesWithEveryFact | the trigger itself is not kept: timed mode is deliberately not in the SDK |
| linearAdWithSkipOffset | COVERED | PayloadsTest.aLinearAdHasItsDurationAndSkipOffset | creative VIDEO now also asserted in the new linear test |
| aLinearAdWithoutSkipOffsetHasNoCloseBeforeComplete | NEW | ParityPayloadsTest.aLinearAdIsAVideoAndWithoutSkipOffsetHasNoCloseBeforeTheEnd | mutation: absent skipOffset -> 0 |
| anHtmlCreativeDecodesAndAnUnknownKindIsRejected | NEW | ParityPayloadsTest.anHtmlCreativeDecodes (+ GapsTest for the unknown kind) | mutation: `"html"` refused |
| aRewardOnTheWireIsIgnored | NEW | ParityPayloadsTest.fieldsTheSdkNeverReadsDoNotFailTheAd | mutation: AdPayload refuses `placementId` (one refusal stands for the extras the test lists) |
| trackingEntriesKeepOffsetAndPart | NEW | ParityPayloadsTest.trackingEntriesKeepOffsetAndPart | mutation: `part = null` |
| anAdWithoutAVastUrlDecodes | COVERED | PayloadsTest.anOverlayDecodesWithEveryFact | Kotlin fixtures carry no `vastUrl` at all |
| unknownTriggerKindIsRejected | COVERED | PayloadsTest.nonsenseIsRefused | `timed` refused |
| unknownRenderingKindIsRejected | COVERED | GapsTest.thePayloadsLastRefusals | `vast` refused |
| aPlacementIdOnTheWireIsIgnored | NEW | ParityPayloadsTest.fieldsTheSdkNeverReadsDoNotFailTheAd | mutation: AdPayload refuses `placementId` |
| unknownFormatIsRejected | COVERED | GapsTest.thePayloadsLastRefusals | `banner` refused |
| aConfigAnswerNeedsABooleanEnabledAndToleratesTheRest | NEW | ParityPayloadsTest.aConfigAnswerNeedsABooleanEnabledAndToleratesTheRest | mutation: `reportDebug` = present-ness instead of a boolean (also fails on the default-reason mutation) |
| anAdsAnswerEdges | NEW | ParityPayloadsTest.anAdsAnswerEdges | mutation: the format check looks at the first ad only (`ads.take(1)`) |
| adPayloadEdges | FIXED | ParityPayloadsTest.aScalableFlagOfTheWrongTypeIsAMalformedSize, anExpiryBelowZeroIsRefusedAndAZeroOverlayDurationIsNot, theFrameUrlMustBeAbsoluteWithAHostAndIsKeptAsGiven | estimated price on the ad: covered by VideoAdsTest.formatReturnsTheAdAndListsItAsHeldNextTime. DIVERGENCE: Kotlin read `"scalable": "no"` as scalable; Swift refuses it. Fixed in AdPayload.sizes; the test failed on the old code |
| anErrorItemIsSummarizedByTitleThenDetailThenCode | NEW | ParityPayloadsTest.anErrorItemIsSummarizedByTitleThenDetailThenCode | title and non-object were covered (PayloadsTest.theErrorBodyIsReadLeniently); detail/code fallbacks, empty title, `{}` were not. mutation: `title ?: code ?: detail` |
| errorResponseNeedsOnlyTheCode | NEW | ParityPayloadsTest.anErrorLineSaysWhatEachItemCanSay | mutation: items joined with `", "` |
| errorResponseSurvivesAnItemWithoutACodeOrWithWrongTypes | NEW | ParityPayloadsTest.anErrorLineSaysWhatEachItemCanSay | same mutation |
| errorResponseDecodes | NEW | ParityPayloadsTest.anErrorLineSaysWhatEachItemCanSay | string code and absent detail; same mutation |

## RequestTests.swift (18)

| Swift test | Status | Kotlin test | Note |
|---|---|---|---|
| configRequestCarriesIdentityAndAttribution | NEW | ParityRequestsTest.theConfigRequestCarriesTheIdentityAndEachHasItsOwnId | token and sdk name were covered (VideoAdsTest.initializeAsksConfigAndEnables); lowercase UUID, installId, bundleId, os name, brand were not. The `attribution` lists are iOS-only. mutation: `/config` requestId uppercased |
| eachConfigRequestHasItsOwnId | NEW | ParityRequestsTest.theConfigRequestCarriesTheIdentityAndEachHasItsOwnId | same test (two initializations) |
| adsRequestBodyHasEveryField | NEW | ParityRequestsTest.theAdsRequestBodyHasEveryField | user, variant, regulatory, labels, playhead, held were covered (GapsTest), ifa type (BoundaryTest); the rest was not. `attStatus` / `idfv` are iOS-only. mutation: `limitAdTracking` true |
| anAdsRequestCarriesExactlyWhatWasSetAndNothingElse | NEW | ParityRequestsTest.anAdsRequestCarriesExactlyWhatWasSetAndNothingElse | the ad id as given: BoundaryTest.theAdvertisingIdIsAnAaid. mutation: `labels` always sent |
| adsRequestOmitsIfaWhenNotAuthorized | COVERED | GapsTest.theRequestCarriesTheUserConsentLabelsAndVariant (no ifa), VideoAdsTest.formatReturnsTheAdAndListsItAsHeldNextTime (`held` empty list) | `attStatus` iOS-only |
| snapshotIsTheKeyWindowInItsOrientationFullScreen | NEW | ParityRequestsTest.thePlayerIsTheScreenInItsOrientationFullScreen | landscape qualifier. Seconds-to-ms rounding N/A: Kotlin takes ms. mutation: width and height swapped in `AdsRequest.player` |
| deviceFactsHaveTheWireShape | NEW | ParityRequestsTest.deviceFactsHaveTheWireShape | form factor vocabulary: BoundaryTest/GapsTest. The user agent is the WebView's, stubbed in tests. mutation: `darkMode` sent as a string |
| trackingInfoWithoutTheDialogReportsTheStatus | N/A | | App Tracking Transparency is iOS-only |
| concurrentRequestsShowTheDialogOnceAndBothGetTheAnswer | N/A | | ATT dialog, iOS-only |
| anAppThatNeverAskedForTrackingIsWarnedOnceAtTheFirstRequest | N/A | | ATT, iOS-only |
| theIDFAIsSentOnlyWhenAuthorizedAndReal | N/A | | ATT/IDFA, iOS-only. The Android twin (limited ad tracking or the zero UUID -> no id) lives in KontextKit's AdvertisingIdProvider and its tests |
| readingTheDeviceLeavesTheAppsAudioSessionAsItWas | N/A | | AVAudioSession, iOS-only; Android reads the stream volume without a session |
| consentStorageReadsTheIABKeys | NEW | ParityRequestsTest.consentReadsTheIabKeysAndCoppaOnlyFromThePublisher | GPP/USP keys were covered in GapsTest, TCF keys were not. mutation: wrong US Privacy key |
| consentStorageTheCMPWinsAndThePublisherFillsTheRest | NEW | ParityRequestsTest.consentPrecedenceForEveryField | mutation: the publisher's `gpp` wins over the CMP's |
| consentPrecedenceForEveryField | NEW | ParityRequestsTest.consentPrecedenceForEveryField | same mutation |
| consentStorageReadsGdprAppliesAsCMPsWriteIt | NEW | ParityRequestsTest.gdprAppliesIsReadAsCmpsWriteIt | normalization is KontextKit's TCFDataProvider. mutation: `gdpr = it >= 0` |
| consentStorageIsNilWhenNothingIsKnown | NEW | ParityRequestsTest.consentIsNullWhenNothingIsKnown | mutation: the empty Regulatory is returned instead of null |
| consentStorageParsesSingleAndCommaSeparatedSids | FIXED | ParityRequestsTest.gppSectionsAreReadSingleUnderscoredCommaSeparatedOrAsANumber | DIVERGENCE: Kotlin read neither `"8,10"` nor a section stored as a number. Fixed in Consent; the test failed on the old code; mutation after the fix: the `Int` branch removed |

## ModelEncodingTests.swift (17)

| Swift test | Status | Kotlin test | Note |
|---|---|---|---|
| contentEncodesRequiredFieldsOnly | COVERED | LogicTest.theContentLeavesOutWhatTheServerWouldRefuse | exact `{"id":"ep","language":"en"}` |
| contentEncodesEverythingInMilliseconds | NEW | ParityPayloadsTest.contentEncodesEveryFieldAndNothingElse | whole object compared. mutation: `genre` dropped |
| aUserIsSentExactlyAsGiven | NEW | ParityPayloadsTest.aUserIsSentExactlyAsGivenAndOnlyWhatIsSet | mutation: email trimmed |
| aDurationThatIsNotADurationIsLeftOut | COVERED | LogicTest.theContentLeavesOutWhatTheServerWouldRefuse (0), ParityPayloadsTest.numbersBelowOneAndASeriesWithoutAnIdAreLeftOut (negative) | NaN/infinity N/A: Kotlin takes `durationMillis: Long` |
| episodeNumbersAndCountsBelowOneAndAnEmptySeriesAreLeftOut | NEW | ParityPayloadsTest.numbersBelowOneAndASeriesWithoutAnIdAreLeftOut | episodeCount below 1 with the series kept was not covered. mutation: episodeCount without the `>= 1` filter |
| aUserSendsWhatIsSetAndLeavesEmptyStringsOut | NEW | ParityPayloadsTest.aUserIsSentExactlyAsGivenAndOnlyWhatIsSet | empty id was covered (LogicTest); empty keywords, hash only, all empty were not. Same mutation |
| theProblemsNameWhatThePublisherPassedWrong | NEW | ParityPayloadsTest.theProblemsNameWhatThePublisherPassedWrong | exact phrases (Kotlin says `durationMillis -1`, it takes ms) and the blank rule. mutation: `id.isBlank()` |
| contentDurationRoundsToNearestMillisecond | N/A | | Kotlin takes milliseconds; nothing to round |
| contentIsEquatable | COVERED | ModelsTest.contentFromTheJavaBuilderEqualsTheKotlinOne | equal and not equal |
| userEncodesOnlySetFields | NEW | ParityPayloadsTest.aUserIsSentExactlyAsGivenAndOnlyWhatIsSet | exact keys. Same mutation |
| userEquality | NEW | ParityPayloadsTest.aUserIsSentExactlyAsGivenAndOnlyWhatIsSet | equality was covered (ModelsTest), inequality was not. Asserted in the same test |
| regulatoryEncodesEveryField | NEW | ParityPayloadsTest.regulatoryEncodesEveryFieldAndOmitsWhatIsUnset | mutation: `gdprConsent` dropped |
| regulatoryOmitsNilFields | NEW | ParityPayloadsTest.regulatoryEncodesEveryFieldAndOmitsWhatIsUnset | same test |
| adFormatRawValuesMatchTheWire | COVERED | ModelsTest.formatsHaveTheirWireNames | names and order |
| configurationDefaults | NEW | ModelsTest.configurationDefaultsAndBuilder + ParityPayloadsTest.theDefaultServerIsProductionAndTheLogLevelsAreOrdered | the literal production URL was not asserted. mutation: the constant changed. `requestTrackingAuthorization` iOS-only |
| logLevelsAreOrdered | NEW | ParityPayloadsTest.theDefaultServerIsProductionAndTheLogLevelsAreOrdered | PartsTest.theLogShowsOnlyWhatItsLevelLets covers part of it. Same mutation fails the test (shared) |
| sdkInfoCarriesTheNameAndAReleaseVersion | NEW | ParityPayloadsTest.theSdkCarriesItsNameAndAReleaseVersion | name was covered (VideoAdsTest); the version rule was not. mutation: version sent as `"v" + VERSION` |

## PublicTypesTests.swift (7)

| Swift test | Status | Kotlin test | Note |
|---|---|---|---|
| everyErrorReadsAsASentence | NEW | ParityPayloadsTest.everyExceptionReadsAsASentence | ModelsTest checked non-blank and distinct only; now every exact sentence (Android wording). mutation: NotLoaded's message shortened. See the question about `Request` |
| aMinimumSizeIsDescribedWithoutATrap | COVERED | ModelsTest.everyExceptionSaysWhatHappened | `480×120`. Non-finite and fractional N/A: Kotlin's minimum is Int pixels |
| everyNoFillReasonMapsFromTheWireAndBack | NEW | ParityPayloadsTest.everyNoFillReasonMapsFromTheWireAndBack | LogicTest covered geoRestricted only. mutation: AlreadyUsed's wire name `alreadyused` |
| delegateMethodsAllHaveDefaults | COVERED | GapsTest.aListenerNeedsNoMethods | |
| delegateIsHeldWeakly | N/A | | Kotlin holds the listener strongly, as Android listeners are, and drops it at `onAdEnded` (VideoAd.end). Listed as a question |
| endReasonsAndClicksAreEquatable | COVERED | EdgesTest.theValueTypesDifferByEachField, ModelsTest.theValueTypesCompareByValue, LogicTest.theWireNamesMapBothWays | EndReason is an enum |
| everyFormatThrowsNotInitializedBeforeInitialize | NEW | ParityRequestsTest.everyFormatThrowsNotInitializedBeforeInitialize | RacesTest covered PAUSE only. mutation: LINEAR throws Destroyed before initialize |

## Counts

94 Swift tests: 26 COVERED, 50 NEW (one of them, the SKAN-malformed test, only for its Android part), 2 FIXED, 16 N/A.

## Parity: the VideoAd lifecycle (show, placement, ending, reentrancy, the linear screen)

Kotlin tests marked NEW are in `video/src/test/kotlin/so/kontext/video/ParityLifecycleTest.kt` (P = ParityLifecycleTest). Other test classes are `VideoAdLifecycleTest` (VAL), `BoundaryTest`, `EdgesTest`, `PartsTest`, `ReentryTest`, `GapsTest`, `VideoAdsTest`, `internal/PayloadsTest`, `internal/AndroidPartsTest`, `internal/FrameMessagesTest`, `internal/TrackerTest`.

Totals over 129 Swift tests: **COVERED 36, NEW 66, FIXED 1, N/A 26**.

## VideoAdTests.swift (77)

| Swift test | Status | Kotlin test | Note |
|---|---|---|---|
| pauseAdExposesItsFacts | NEW | P.aPauseAdExposesItsFactsAndHasNoDuration | Decoding is covered by PayloadsTest; the VideoAd-level facts were not. Mutation: price forced to EXACT |
| overlayAdExposesSecondsAndATimedAnswerIsAnOrdinaryAd | COVERED | PayloadsTest.anOverlayDecodesWithEveryFact; GapsTest.thePayloadsLastRefusals | durationMillis 6000; "a timed answer is still an ad" |
| linearAdExposesVideoFacts | COVERED | PayloadsTest.aLinearAdHasItsDurationAndSkipOffset, aFullScreenPauseAdHasNoSizes | |
| notLoadedAdIsNotReady | COVERED | VAL.showRefusesAnAdNotLoaded; EdgesTest.anAdIsReadyOnlyLoadedUnshownAndValid | |
| expiryIsFoundOutAtShowNotByATimer | NEW | P.expiryIsFoundOutAtTheNextCallNotByATimer | VideoAdsTest.anExpiredAdIsDroppedSilently already checked the throw; the new test checks invalidationReason stays null until the call. Mutation: isReady invalidates on expiry |
| showPlacesTheAdAtTheTopAndReportsShowAndImpression | NEW | P.aPauseCardsHostCoversTheContainerAndHoldsTheCreativeAtTheTopCentred | The host covers the container and holds the WebView; card at the top, centred; ad.show first; no overlayViewDuration at show. Mutation: TOP placed at the bottom |
| anHtmlCreativeCountsItsImpressionAtShowLikeAnImage | NEW | P.anHtmlCreativeCountsItsImpressionAtShowLikeAnImage | Mutation: impression at show only for IMAGE |
| showPlacesABottomOverlayAtTheBottom | COVERED | PartsTest.theHostRefitsTheAdWhenTheContainerResizesAndEndsItWhenTooSmall | Rect(0, 320, 320, 400) |
| fullscreenPauseAdFillsThePlayer | NEW | P.aFullScreenPauseAdFillsTheContainerWhateverItsSizeAndInsets | Mutation: refit applies the insets to full screen |
| showThrowsContainerTooSmallBelowHalfTheSizeAndTheAdStaysReady | NEW | P.aContainerTooSmallLeavesNothingBehindAndABiggerOneShowsTheAd (+ VAL.showRefusesAContainerTooSmallAndSaysTheMinimum) | Nothing added or fired; a bigger container then shows it. Mutation: wasShown set before chooseSize |
| showTwiceThrowsAlreadyShown | NEW | P.aSecondShowWhileTheAdIsOnScreenIsAlreadyShown | VAL checked AlreadyShown only after dismiss. Mutation: AnotherAdShowing check before AlreadyShown |
| showInOnALinearAdThrowsWrongPresentation | COVERED | VAL.showRefusesTheWrongPresentation | |
| showPlacesTheAdInTheViewItIsGiven | COVERED | PartsTest.theHostRefits… (getChildAt(0) as AdHostView); VAL.dismissEndsItAtOnceWithItsViewDuration (childCount 0) | |
| showWhileAnotherAdIsOnScreenAnywhereInTheAppThrowsAnotherAdShowing | NEW | P.anAdOnScreenInOneVideoBlocksAShowInAnother | VAL used one VideoAds. Mutation: the slot checked per owner |
| anOverlaysClockFreezesWhileItsSceneIsNotActive | NEW | P.anOverlaysDurationFreezesWhileItsWindowIsHidden | Through the host's window visibility (the Android counterpart of the scene); the clock alone is covered by PartsTest/ReentryTest. Mutation: host.onWindowVisibility not wired |
| anOverlayShownWhileInactiveStartsItsClockOnActivation | COVERED | ReentryTest.theClockIsIdempotentAndCountsOnlyWhileVisible | start(visible = false) then visible counts. Through show it can't happen on Android: show refuses a container whose window isn't visible |
| aPagesCloseWithReasonCompleteEndsCompletedWithoutACloseBeacon | NEW | P.aLinearPageEndingItselfAfterTheVideoIsCompletedWithoutAClose | Only the overlay version existed (VAL.thePageEndingItselfIsCompletedWithoutAClose). Mutation: Complete no longer sets isCompleted |
| anOverlayIsRemovedBySDKWhenItsDurationElapses | NEW | P.anOverlayWhoseDurationElapsesLeavesItsContainerAndFiresItsViewDurationNotClose | VAL.anOverlayWithoutAPlayerEndsWhenItsDurationElapses had only the reason. Mutation: no overlayViewDuration on COMPLETED |
| dismissBeforeTheDurationEndsAnOverlayEarlyAndStopsTheClock | NEW | P.aDismissBeforeTheDurationEndsTheOverlayEarlyAndStopsItsClock | Mutation: clock not stopped at the end, and its callback unguarded (two guards) |
| anOverlayWithoutADurationStaysUntilTheAppEndsIt | NEW | P.anOverlayWithoutADurationAndAPauseAdStayUntilEnded | Mutation: a missing duration defaults to 1 s |
| aPauseAdHasNoDurationAndStaysUntilDismissed | NEW | P.anOverlayWithoutADurationAndAPauseAdStayUntilEnded | same test |
| dismissEndsWithDismissedAndFiresOverlayViewDurationNotClose | COVERED | VAL.dismissEndsItAtOnceWithItsViewDuration | "Not close" is checked with close in the tracking by P.aDismissBeforeTheDuration… |
| dismissBeforeShowDoesNothing | NEW | P.dismissBeforeShowDoesNothing | Mutation: the !wasShown guard removed from dismiss() and endNow (two guards) |
| closeEndsWithClosedAndFiresClose | COVERED | VAL.theOverlaysCloseControlEndsItAsClosed | |
| resumeEndsWithResumed | COVERED | VAL.aFullScreenPauseAdsResumeEndsItAsResumed | |
| aPauseAdsVideoCompletingDoesNotChangeHowItsCloseEnds | NEW | P.aPauseAdsVideoCompletingLeavesItsEndToTheViewer | Mutation: complete ends a pause ad |
| overlayViewDurationCarriesTheActiveTimeOnScreenInMilliseconds | NEW | P.anOverlaysViewDurationIsItsActiveTimeOnScreen | Deterministic clock; hidden time is left out. Mutation: window visibility not wired |
| aCreativeThatMayNotShrinkIsNeverShownSmaller | NEW | P.aCreativeThatMayNotShrinkNeedsItsOwnSize (+ AndroidPartsTest.anAdIsScaledToFit…, EdgesTest "one may not shrink: the other, shrunk") | The minimum was its own size, which nothing checked. Mutation: smallestScale always 0.5 |
| aCreativeThatMayNotShrinkShowsAtItsOwnSizeWhenItFits | COVERED | AndroidPartsTest.anAdIsScaledToFitNeverEnlargedAndRefusedBelowHalf; P.aPauseCardsHost… (300×250 non-scalable at its own size) | |
| showPicksTheLargestSizeThatFitsTheViewUnscaled | COVERED | EdgesTest.theBiggestVariantThatFitsUnscaledWinsElseTheOneShrunkLeast | via bridge.chosenSize, which ad.show sends |
| showFallsBackToTheBestScaledSizeWhenNoneFits | COVERED | EdgesTest (same, "both shrink: the least shrunk") | |
| aClickThroughWithTheFireIdMacroOpensWithTheClickBeaconsFireId | NEW | P.aClickThroughCarriesTheClickBeaconsOwnFireId | TrackerTest covered the substitution only. Mutation: the click-through gets another fire id |
| clickOpensTheURLFiresClickAndTellsTheDelegate | COVERED | VAL.aClickAfterATouchFiresItsBeaconOpensTheTargetAndTellsTheListener | |
| clickFallsBackWhenTheDeepLinkCannotOpen | COVERED | EdgesTest.aClickOpensItsFallbackWhenNothingHandlesTheTarget | |
| adChoicesOpensWithoutAClickAndFiresIconClick | COVERED | VAL.theAdChoicesLinkFiresTheIconClickAndOpens | |
| aLinearAdCoveredByAnotherScreenWaitsAndEndsOnceLater | NEW | P.aLinearScreenCoveredByAnotherWaitsAndEndsOnceLater | Covered = LinearAdActivity paused and stopped. Zero exposure while covered isn't asserted (meter's area; Robolectric's geometry isn't real). Mutation: onStop ends the ad |
| aCoveredLinearAdWhoseViewNeverReturnsEndsWhenTheCoverGoes | NEW | P.aCoveredLinearScreenThatTheSystemThenDestroysIsInterruptedOnce | On Android the screen gets destroyed. Mutation: onDestroy not reported and the meter's onDetached off (either one alone still ends it) |
| aLinearAdWhoseViewLeavesWithNothingCoveringItIsInterrupted | **FIXED** | P.aLinearPageTakenOffItsScreenIsInterrupted | the ad ended INTERRUPTED but left its live, empty screen up, where Back does nothing; endNow now closes it, and onAdEnded arrives once, after the screen is gone. Failed on the old code |
| anOpenForAnotherPurposeOpensTheURLAndFiresNothing | NEW | P.anOpenForAnotherPurposeOpensItsUrlAndFiresNothing | Mutation: iconClick for any purpose |
| errorAfterShowIsDidFailThenDidEndFailed | COVERED | VAL.aCreativeThatBreaksOnScreenFailsThenEnds | |
| errorWhileLoadedButUnshownEndsTheAdSilently | NEW | P.aCreativeThatDiesBeforeShowTellsTheListenerNothing (+ VAL.aCreativeThatDiesBeforeShowEndsTheAdAndShowSaysSo) | Mutation: onAdFailed before show |
| videoImpressionWaitsForTheFirstFrame | NEW | P.aVideoImpressionWaitsForTheFirstFrameAndCountsOnce (+ VAL.aLinearAdPlaysThrough…) | A second video.start was untested. Mutation: impressionRecorded guard removed |
| aProgressCheckpointThePageSkippedStillFires | NEW | P.aQuartileFiresTheCheckpointsItsPlayheadPassed | Mutation: no fireProgress in the video branch |
| aMalformedProgressOffsetFiresNoCheckpointEarly | COVERED | FrameMessagesTest.progressTakesOnlyAWholeNumberOfMillisecondsWithinADay | The bridge drops the offset, so nothing reaches the tracker |
| videoEventsFireTheirBeaconsWithTheAdPlayhead | NEW | P.everyVideoMomentFiresItsBeaconWithTheAdPlayhead | pause/resume/mute/unmute and [ADPLAYHEAD] were untested. Mutation: adPlayheadMillis not set by video events |
| completeFiresCompleteAndTheEndCardsCreativeView | COVERED | VAL.aLinearAdPlaysThroughWithEveryBeaconAndClosesFromItsEndCard | |
| endCardClickUsesTheEndCardsEntry | NEW | P.anEndCardClickFiresTheEndCardsEntryOnly | Mutation: click part always null |
| skipEndsWithSkipped | NEW | P.everyVideoMomentFiresItsBeaconWithTheAdPlayhead (skip aph, no overlayViewDuration) | SKIPPED was covered by VAL.aLinearAdClosedBeforeTheEndIsSkipped |
| showFromPresentsFullScreenAndTheSDKDismissesAtTheEnd | NEW | P.theLinearScreenHoldsThePageFullScreenOnBlackAndTheAdClosesIt | Mutation: endNow doesn't finish the screen. Hidden system bars need an emulator (not asserted) |
| dismissingFromInsideTheImpressionCallbackLeavesNothingRunning | NEW | P.dismissInsideTheImpressionLeavesNothingRunning (+ ReentryTest.aListenerThatDismissesAtTheImpressionHearsOnlyTheEnd) | clock/meter/bridge released. Mutation: meter not cleared at the end |
| aMessageAfterTheEndChangesNothing | COVERED | ReentryTest.eventsAfterTheEndChangeNothing | |
| aPresenterStillDismissingItsModalIsNotUsed | N/A | — | UIKit modal state; Android's show(activity) takes the Activity, and a finishing one is refused (VAL.aLinearShowFromAFinishingActivityIsRefused) |
| aPresentationUIKitNeverCompletesEndsTheAdAsFailed | NEW | P.aLinearScreenThatNeverStartsEndsTheAdAsFailedWithNothingBillable | The Activity never created; BoundaryTest covered "created, never resumed". Mutation: no fail() on the timeout |
| aControllerOnScreenInAnInactiveAppIsNotTakenAsPresented | COVERED | BoundaryTest.aLinearScreenThatNeverComesToTheFrontEndsTheAd | started, never resumed |
| destroyingInsideDidFailKeepsTheFailedReason | NEW | P.aDestroyInsideOnAdFailedKeepsTheFailedReason | Mutation: hasEnded set after onAdFailed |
| aControllerOnScreenWhoseCompletionNeverCameIsTakenAsPresented | COVERED | VAL.aLinearAdPlaysThrough… (creativeView once resumed) | Android has no completion; onResume is the signal |
| aVideoIsViewableOnlyWhileItPlays | NEW | P.aVideoCountsTowardsViewabilityOnlyWhilePlaying | The page-events → meter.countsTime wiring. The viewable timing is the meter's suite. Mutation: pause leaves videoPlaying set |
| aVideosViewabilityClockStopsAtComplete | NEW | P.aVideoCountsTowardsViewabilityOnlyWhilePlaying | same test |
| aVideoThatStartedBeforeTheLinearMeterExistsStillCountsAsPlaying | NEW | P.aLinearVideoThatStartedBeforeItsScreenCameUpCountsOnceItIsUp | Mutation: video events don't update meter.countsTime |
| destroyingFromInsideTheVideoImpressionCallbackFiresNothingAfterTheEnd | NEW | P.aDestroyInsideTheVideoImpressionEndsTheAdAndNothingFiresAfter | Mutation: handleNow's ended guard removed |
| aSkipPastACheckpointFiresItOnce | COVERED | VAL.theVideosSkipFiresTheCheckpointsItPassed; ReentryTest.eventsAfterTheEndChangeNothing (a skip after the end) | |
| aSkipBeforeTheCheckpointFiresNoProgress | COVERED | TrackerTest.progressFiresEveryPassedCheckpointInOrder | only passed checkpoints fire |
| showInAViewThatIsNotOnScreenThrowsPresenterUnavailableAndKeepsTheAd | COVERED | VAL.showRefusesAContainerNotOnScreen; P.aViewTheViewerCannotSee… (ready afterwards) | |
| showFromWhenNotOnScreenThrowsPresenterUnavailableAndKeepsTheAd | COVERED | VAL.aLinearShowFromAFinishingActivityIsRefused | Android's "cannot present" |
| showFromWhenAlreadyPresentingThrowsPresenterUnavailable | N/A | — | An Activity can always start another; no "already presenting" state |
| showWithoutAPresenterUsesTheKeyWindowsTopViewController | N/A | — | No show() without an Activity on Android |
| showWithoutAPresenterAndNoKeyWindowThrowsPresenterUnavailableAndKeepsTheAd | N/A | — | same |
| aLinearAdsCloseBeforeCompleteIsASkip | COVERED | VAL.aLinearAdClosedBeforeTheEndIsSkipped | |
| showFromOnAPauseAdThrowsWrongPresentation | COVERED | VAL.showRefusesTheWrongPresentation | |
| dismissOnALinearAdThrowsWrongPresentation | COVERED | VAL.showRefusesTheWrongPresentation | |
| dismissalFromOutsideIsInterrupted | COVERED | VAL.aLinearScreenTakenAwayInterruptsTheAd | |
| destroyWhileAnLinearIsUpEndsWithDestroyedAndDismisses | NEW | P.aDestroyWhileTheLinearScreenIsUpClosesItAndEndsAsDestroyed | BoundaryTest only ended a linear ad with no screen. Mutation: DESTROYED doesn't finish the screen |
| theSDKRetainsAShowingAdUntilItEnds | COVERED | VAL.anImageOverlayRecordsItsImpressionAtShow (Sdk.adOnScreen), VAL.dismissEndsItAtOnce… (null after) | Strong reference in Sdk; release in BoundaryTest.anEndedOverlayDoesNotKeepItsDestroyedOwnerAlive |
| anAdOnScreenIgnoresInvalidationAndFinishesNormally | NEW | P.anAdOnScreenIgnoresInvalidationAndExpiry | Mutation: the wasShown guard removed from invalidate |
| theViewLeavingTheWindowEndsAnInlineAdAsInterrupted | NEW | P.theContainerLeavingTheWindowEndsTheAdAsInterrupted | Mutation: meter.onDetached not wired |
| aViewReParentedAtOnceDoesNotInterruptTheAd | NEW | P.aContainerReParentedAtOnceDoesNotEndTheAd | Mutation: the meter ends the ad on any detach (attach listener) |
| dismissAfterTheEndDoesNothing | COVERED | VAL.dismissEndsItAtOnceWithItsViewDuration | |

## VideoAdPlacementTests.swift (16)

System bars come from a container whose window reports them (Robolectric reports none). The host's placement is called with those same insets, because Robolectric's WebView keeps no bounds after layout.

| Swift test | Status | Kotlin test | Note |
|---|---|---|---|
| aBottomOverlayStaysAboveTheHomeIndicator | NEW | P.theSystemBarsOverTheContainerKeepAStripAndACardClear | Mutation: systemInsets returns zero |
| aTopPauseCardStaysBelowTheNotch | NEW | same | |
| anAdFitsBetweenTheSideInsetsInLandscape | NEW | P.anAdFitsBetweenTheSideBarsInLandscape | |
| showRefusesAViewTheViewerCannotSee | NEW | P.aViewTheViewerCannotSeeIsRefusedAndTheAdStaysReady (+ VAL.showRefusesAContainerNotOnScreen for a hidden view) | Transparent ancestor, hidden window, zero area. Linear "in a hidden window" doesn't apply (an Activity is the presenter). Mutation: canBeSeen's alpha walk removed |
| aShownAdFollowsTheViewWhenItResizesAndEndsWhenItNoLongerFits | NEW | PartsTest.theHostRefits… (resize, end); P.touchesOutsideTheCreativeReachThePlayer | The touch pass-through was untested. Mutation: host clickable |
| aFullscreenPauseAdFollowsAResizedPlayer | NEW | P.aFullScreenPauseAdFillsTheContainerWhateverItsSizeAndInsets | |
| theMinimumOfContainerTooSmallIncludesTheSafeArea | NEW | P.theMinimumOfContainerTooSmallCountsTheSystemBarsAndAContainerOfItHoldsTheAd | |
| insetsAddToTheSafeAreaForAStripAndACard | NEW | P.theAppsInsetsMoveAStripAndACardAndStayThroughAResize | Mutation: refit ignores the app's insets |
| aFullscreenPauseAdIgnoresTheInsets | NEW | P.aFullScreenPauseAdFills… | |
| theInsetsStayThroughAResize | NEW | P.theAppsInsetsMoveAStripAndACardAndStayThroughAResize | |
| theMinimumOfContainerTooSmallIncludesTheInsets | NEW | P.theMinimumOfContainerTooSmallCountsTheAppsInsets | Mutation: chooseSize ignores the app's insets |
| insetsThatLeaveNoWidthOrNoHeightAreTooSmall | NEW | P.insetsThatLeaveNoWidthOrNoHeightLeaveTheAdReady (VAL.insetsThatLeaveNoRoomAreTooSmall had height only) | Mutation: the room check removed and abs() in scale (the inverted-rectangle bug the Swift test guards) |
| hugeFiniteInsetsAreTooSmallAndNeverTrap | **FIXED** | P.hugeInsetsAreTooSmallWithAMinimumThatDoesNotOverflow, P.anInsetTooLargeToAddToTheSystemBarsIsTooSmall | Kotlin never trapped, but Int arithmetic wrapped: Insets.of(Int.MAX_VALUE, …) gave ContainerTooSmall(-2147483489×40). Both tests failed on the old code |
| resizingUntilTheInsetsLeaveNoRoomEndsTheAd | NEW | P.resizingUntilTheAppsInsetsLeaveNoRoomEndsTheAd | |
| aNegativeOrNonFiniteInsetCountsAsZero | NEW | P.aNegativeInsetCountsAsZero | Non-finite doesn't apply (Insets are Int). Mutation: no clamp to 0 |
| aFullscreenPauseAdFillsThePlayerEvenUnderTheSafeArea | NEW | P.theSystemBarsOverTheContainerKeepAStripAndACardClear | |

## VideoAdReentrancyTests.swift (12)

The playhead provider is the tracked `VideoAdsPlayer.currentPositionMillis`.

| Swift test | Status | Kotlin test | Note |
|---|---|---|---|
| aProviderThatDestroysAtShowMeansNothingIsShown | NEW | P.aPlayheadReadThatDestroysAtShowMeansNothingIsShown | Mutation: show() not wrapped in withContentPlayhead |
| aShowReadsTheProviderOnceAndEveryBeaconOfItUsesThatValue | NEW | P.aShowReadsThePlayheadOnceForEveryBeaconOfIt | Mutation: Tracker ignores the frozen playhead |
| aProviderThatDestroysAtAClickDropsTheClickAndKeepsDidEndLast | COVERED | ReentryTest.aPlayheadReadThatEndsTheAdDropsTheClick | Ends with dismiss instead of destroy; same rule (no click beacon or listener call, end last) |
| aProviderThatEndsTheAdOnEveryReadIsReadOnce | NEW | P.aPlayheadReadThatEndsTheAdOnEveryReadIsReadOnce | Mutation: the playhead isn't frozen before the read (re-entry reads again) |
| aClickWhoseURLsAloneAskForThePlayheadReadsItOnce | NEW | P.aClickWhoseUrlsAloneAskForThePlayheadReadsItOnce | Mutation: handle() doesn't pass the click URLs |
| aDestroyFromTheParkingWindowsHookWhileShowingEndsTheAdWithNothingShown | N/A | — | No parking window on Android: a loading WebView has no parent the app could hook |
| aShowFromTheParkingWindowsHookFindsTheSlotTaken | N/A | — | same |
| aDestroyFromTheParkingWindowsHookEndsALinearAdBeforeUIKitPresentsIt | N/A | — | same |
| hostileProviderThroughEveryOperationKeepsTheGuarantees | NEW | P.aHostilePlayheadReadThroughEveryOperationKeepsTheGuarantees | 4 hostile actions × 8 operations. Mutation: finish() doesn't free the slot |
| aShowFromInsideTheContainersAddHookFindsTheSlotTaken | NEW | P.aShowFromTheContainersAddHookFindsTheSlotTaken | Hook in onViewAdded. Mutation: reserve() moved after addView |
| aDestroyFromInsideTheContainersAddHookEndsTheAdWithNothingLeft | NEW | P.aDestroyFromTheContainersAddHookEndsTheAdWithNothingLeft | Mutation: hostView assigned after addView |
| aHookThatTakesThePlayerOffScreenEndsTheAdWithoutAnImpression | NEW | P.aHookThatTakesTheContainerOffScreenEndsTheAdWithoutAnImpression | BoundaryTest only covered the hook removing or hiding the host. Mutation: post-add canBeSeen(container) check removed |

## LinearViewControllerTests.swift (5)

| Swift test | Status | Kotlin test | Note |
|---|---|---|---|
| removalTheAdDidNotAskForIsAnInterruption | COVERED | VAL.aLinearScreenTakenAwayInterruptsTheAd | |
| aCoveredViewIsNotAnInterruption | NEW | P.aLinearScreenCoveredByAnotherWaitsAndEndsOnceLater | onPause/onStop |
| theAdsOwnDismissalIsNotAnInterruption | COVERED | VAL.aLinearAdPlaysThrough… (CLOSED after the ad's own finish), VAL.aLinearAdClosedBeforeTheEndIsSkipped | |
| uiKitsDisappearanceCallbackReachesTheDecision | COVERED | VAL.aLinearScreenTakenAwayInterruptsTheAd | Robolectric drives the real onDestroy |
| presentsFullScreenWithTheWebViewFillingIt | NEW | P.theLinearScreenHoldsThePageFullScreenOnBlackAndTheAdClosesIt | Black root, WebView MATCH_PARENT, fade transition. Hidden bars need an emulator. accessibilityViewIsModal doesn't apply (own Activity) |

## VideoAdSKAdNetworkTests.swift (19): all N/A

SKAdNetwork impressions and StoreKit sheets are Apple-only; the Kotlin SDK has no install attribution or store sheet (no such code in `video/src/main`). Each of these tests is iOS-only:

| Swift test | Status | Kotlin test | Note |
|---|---|---|---|
| anAppInstallAdRegistersItsImpressionWhenShownAndEndsItWhenItLeaves | N/A | | SKAdNetwork / StoreKit, Apple-only |
| anAdWithoutAttributionNeverTouchesSKAdNetwork | N/A | | SKAdNetwork / StoreKit, Apple-only |
| aLinearAdRegistersItsImpressionOnceUIKitPresentedIt | N/A | | SKAdNetwork / StoreKit, Apple-only |
| aLinearAdThatNeverGotOnScreenRegistersNoImpression | N/A | | SKAdNetwork / StoreKit, Apple-only |
| theEndWaitsForAStartStillInFlight | N/A | | SKAdNetwork / StoreKit, Apple-only |
| theNextAdsImpressionStartsAfterThePreviousOneEnded | N/A | | SKAdNetwork / StoreKit, Apple-only |
| aFailedRegistrationIsLoggedAndTheAdGoesOn | N/A | | SKAdNetwork / StoreKit, Apple-only |
| aFailedEndStillReleasesTheImpressionBeforeTheNextAd | N/A | | SKAdNetwork / StoreKit, Apple-only |
| anAdThatEndedWhileAppleRegisteredItIsNeverStarted | N/A | | SKAdNetwork / StoreKit, Apple-only |
| anAdThatEndsOnceRegistrationIsUnderwayIsNeverStarted | N/A | | SKAdNetwork / StoreKit, Apple-only |
| everyFailureIsReportedWithItsStepAndAd | N/A | | SKAdNetwork / StoreKit, Apple-only |
| aClickWhoseSheetIsLoadingWhenTheAdEndsIsStillHonored | N/A | | SKAdNetwork / StoreKit, Apple-only |
| anImpressionEndsOnTheManagerItStartedOn | N/A | | SKAdNetwork / StoreKit, Apple-only |
| aFailedStartStillGetsItsEnd | N/A | | SKAdNetwork / StoreKit, Apple-only |
| repeatedStartAndEndAreOneLifecycle | N/A | | SKAdNetwork / StoreKit, Apple-only |
| aClickOnAnAdWhoseNetworkSignedTheClickOpensTheStoreSheetNotTheURL | N/A | | SKAdNetwork / StoreKit, Apple-only |
| aStoreSheetThatCouldNotBePresentedOpensTheURL | N/A | | SKAdNetwork / StoreKit, Apple-only |
| anAdSignedForTheClickOnlyRegistersNoImpressionAndStillOpensTheSheet | N/A | | SKAdNetwork / StoreKit, Apple-only |
| aClickOnAnAdWithOnlyAViewThroughSignatureOpensTheURL | N/A | | SKAdNetwork / StoreKit, Apple-only |

## Source fix

- **Insets overflow** (`VideoAd.combinedInsets`, `AdPlacement.minimum`): the system's and the app's insets, and the minimum, were added as Int, so a huge app inset wrapped around. `ContainerTooSmall` reported a negative minimum (-2147483489 px), and a system inset plus `Int.MAX_VALUE` became a small negative inset. Both now saturate at `Int.MAX_VALUE`, which matches Swift's CGFloat behaviour (no trap, a huge minimum). No public API change.

## Questions

1. Resolved: a linear ad ended `INTERRUPTED` while its screen is alive now closes the screen (see the row above).
2. Hidden system bars and the fade transition on the linear screen: the fade is asserted; the hidden bars need an emulator test (`WindowInsetsControllerCompat.hide` has no Robolectric effect).
3. Mutation testing was done in five batches of mutations to unrelated code paths. Every targeted test failed under its mutation. Three rules are guarded twice (dismiss before show, the overlay clock after an early dismiss, covered-then-destroyed), so each needed a two-part mutation.

## Parity: VideoAds, loading, initialization, integration

Swift: `Tests/KontextVideoSDKTests/{Core/VideoAdsTests, Core/VideoAdLoadingTests, Core/KontextVideoTests, Integration/IntegrationTests}.swift`.
Kotlin: `video/src/test/kotlin/so/kontext/video/`; NEW tests are in `ParityAdsTest.kt`.

## VideoAdsTests (46)

| Swift test | Status | Kotlin test | Note |
|---|---|---|---|
| creatingAVideoAdsSendsNothing | NEW | ParityAdsTest.creatingAVideoAdsSendsNothing | |
| formatAsksForAllFormatsWithTheSessionAndWhatItHolds | NEW | ParityAdsTest.formatAsksForAllFormatsWithTheSessionAndWhatItHolds | VideoAdsTest.formatReturnsTheAdAndListsItAsHeldNextTime had held only; now no `formats`, one session, both bids listed |
| labelsGoWithEveryRequest | NEW | ParityAdsTest.labelsGoWithEveryRequestAndNoneAreLeftOut | |
| labelsThatBreakTheRulesAreDroppedWithAWarning | NEW | ParityAdsTest.labelsThatBreakTheRulesAreDroppedWithOneWarningAndWhatIsKeptIsSent | the drop rules themselves: LogicTest.labelsKeepOnlyWhatTheServerAccepts |
| everyVideoAdsHasItsOwnSession | NEW | ParityAdsTest.everyVideoAdsHasItsOwnSession | |
| thePlayerIsTheScreenInItsOrientationAndThePlayheadIsReadAtRequestTime | NEW | ParityAdsTest.thePlayerIsTheScreenAndThePlayheadIsReadAtRequestTime | screen size from display metrics in dp (Robolectric has no rotation to set) |
| theRequestCarriesConsentFromTheCMPStorageAndTheCurrentTargeting | COVERED | GapsTest.theRequestCarriesTheUserConsentLabelsAndVariant | CMP storage, user, variant read when the request is built |
| aReServedAdIsTheSameObjectStillLoaded | NEW | ParityAdsTest.aReServedAdIsTheSameObjectStillLoaded | same object: RacesTest.aReServedAdIsTheSameObject; new: still loaded, not rendered again |
| aReServedPayloadIsIgnored | NEW | ParityAdsTest.aReServedPayloadIsIgnored | |
| otherFormatsWaitForTheirOwnCall | NEW | ParityAdsTest.otherFormatsWaitForTheirOwnCall | |
| aNewIdReplacesTheHeldBidWhateverTheSDKsClockSays | NEW | ParityAdsTest.aNewIdReplacesTheHeldAdAndTheOldOneRefusesShowAndLoadSilently | Expired reason was in RacesTest.aReplacedAdEndsTheOneHeldAndANoFillDropsIt; new: show/load throw, no notUsed |
| aNoFillDropsTheHeldBidAndIsThrownWithTheReason | NEW | ParityAdsTest.aNoFillDropsTheHeldAdAndTheNextCallAsksAgainHoldingNothing | reason + drop also in RacesTest.aReplacedAdEndsTheOneHeldAndANoFillDropsIt |
| aFormatWithoutAConfigurationIsANoFill | COVERED | VideoAdsTest.aNoFillSaysWhy | `NoFill(FormatNotEnabled)` |
| anExpiredBidIsDroppedSilentlyBeforeTheNextRequest | NEW | ParityAdsTest.anExpiredAdIsDroppedSilentlyBeforeTheNextRequest | |
| aReServedIdThatExpiredDuringTheRequestNeverComesBack | NEW | ParityAdsTest.aReServedIdThatExpiredDuringTheRequestNeverComesBack | |
| aFailedRequestReturnsTheHeldBidWhileItIsValid | COVERED | VideoAdsTest.aFailedRequestReturnsTheHeldAdAndOtherwiseThrows | held returned, other format `Request`, expired held is no fallback |
| aFailedRequestLeavesNothingBehindAndTheNextCallSendsAFreshOne | NEW | ParityAdsTest.aFailedRequestLeavesNothingBehindAndTheNextCallSendsAFreshOne | |
| serverErrorsAreRequestErrorsNeverRetried | NEW | ParityAdsTest.serverErrorsAreRequestErrorsNeverRetried | |
| anEmptyIdOrLanguageStopsADebugBuildAndBadMetadataDoesNot | NEW | ParityAdsTest.anEmptyIdOrLanguageAssertsAndBadMetadataDoesNot | empty id alone was in PartsTest.aVideoAdsWithoutAnIdAsksAndAfterDestroyRefusesEverything |
| destroyWhileTheATTDialogIsUpReleasesTheWaiterAndTheDialogGoesOn | N/A | | iOS tracking-permission dialog; Android has none. The Android wait before sending (user agent read) is ParityAdsTest.destroyWhileTheUserAgentIsReadThrowsDestroyedAtOnceAndTheOthersGoOn |
| beforeInitializeFormatThrowsNotInitialized | COVERED | RacesTest.formatBeforeInitializeIsNotInitialized | |
| aFailedInitIsRetriedByFormatAndThrowsWhenTheRetryFails | NEW | ParityAdsTest.aFailedConfigIsTriedAgainByFormatAndThrowsWhenThatFailsToo | `InitializationFailed` was in RacesTest.aConfigThatFailsTwiceIsInitializationFailed; new: the /config count |
| destroyWhileWaitingForInitThrowsDestroyedAtOnceAndTheOthersShareOneConfig | NEW | ParityAdsTest.destroyWhileWaitingForConfigThrowsDestroyedAtOnceAndTheOthersShareOneConfig | RacesTest.aDestroyWhileConfigRunsEndsTheRequestBeforeItLeaves had the destroyed half only |
| formatWaitsForInitInFlight | NEW | ParityAdsTest.formatAndAwaitInitializationWaitForConfigInFlight | |
| aDisabledResponseSwitchesTheSDKOffAndDropsEveryBidWithoutNotices | NEW | ParityAdsTest.aDisabledResponseSwitchesTheSdkOffAndDropsEveryAdWithoutNotices | state only was in VideoAdsTest.anAdsResponseCanSwitchTheSdkOff |
| aKillSwitchDuringALoadMakesItThrowDisabled | NEW | ParityAdsTest.aKillSwitchDuringALoadMakesItThrowDisabled | |
| switchedOffDuringTheATTDialogNothingLeaves | COVERED | RacesTest.theKillSwitchBeforeSendingSendsNothing | Android analog: switched off while the user agent is read; no ATT on Android |
| switchedOffWhileTheResponseWasOutItsAdsAreNeverHeld | COVERED | RacesTest.theKillSwitchFromAnotherVideoWinsOverAnAnswerInFlight | `Disabled`, `ads` empty (so destroy has nothing to notify) |
| switchedOffWhileAFailingRequestWasOutTheHeldAdDoesNotComeBack | NEW | ParityAdsTest.switchedOffWhileAFailingRequestWasOutTheHeldAdDoesNotComeBack | RacesTest.aFailedRequestAfterTheKillSwitchSaysDisabled had no held ad |
| aDisabledConfigThrowsDisabled | COVERED | VideoAdsTest.theServerCanSwitchTheSdkOff | |
| callsMadeWhileARequestRunsShareIt | NEW | ParityAdsTest.callsForDifferentFormatsWhileARequestRunsShareIt | same-format sharing: RacesTest.twoCallersShareOneRequestAndOneLeavingDoesNotStopIt |
| sequentialCallsEachSendTheirOwnRequest | COVERED | VideoAdsTest.formatReturnsTheAdAndListsItAsHeldNextTime | two calls, two bodies |
| cancellingACallStopsOnlyItsWaitingTheRequestContinuesForTheOthers | COVERED | RacesTest.twoCallersShareOneRequestAndOneLeavingDoesNotStopIt | the caller that left hears nothing, the other gets the ad, one request |
| aBidRejectedWhileARequestRunsIsIgnoredWhenTheResponseReServesIt | NEW | ParityAdsTest.anAdRejectedWhileARequestRunsIsNotTakenBackAndOtherFormatsAreServed | after-the-fact re-serve was in VideoAdsTest.rejectTellsTheServerWhyAndTheIdIsNeverTakenBack |
| aBidShownWhileARequestRunsIsIgnoredWhenTheResponseReServesIt | NEW | ParityAdsTest.anAdShownWhileARequestRunsIsNotTakenBackAndStaysOnScreen | |
| destroyEndsEverythingWithOneNoticePerHeldBid | NEW | ParityAdsTest.destroyEndsEverythingWithOneNoticePerHeldAd | one held overlay was in VideoAdsTest.destroySettlesHeldAdsAndEndsEverything |
| destroyNotifiesValidHeldAdsOnlyAnExpiredOneLeavesSilently | NEW | ParityAdsTest.destroyNotifiesValidHeldAdsOnlyAnExpiredOneLeavesSilently | |
| destroyDuringARequestThrowsDestroyedAndIgnoresTheLateResponse | COVERED | RacesTest.aDestroyDuringTheNetworkWaitDropsTheAnswer | |
| releasingAVideoAdsThatStillHoldsAdsWarnsOnTheConsole | N/A | | no deterministic deinit on the JVM; the publisher calls `destroy()`. See question 1 |
| releasingTheVideoAdsDoesWhatDestroyDoes | N/A | | same; an ad whose VideoAds was collected throws `Destroyed` (`VideoAd.checkUsable`), but no `notUsed` leaves. See question 1 |
| releasingTheVideoAdsMidRequestFreesItTheRequestDoesNotKeepItAlive | N/A | | GC, no deinit; the request's coroutine holds the VideoAds until the call deadline at most. Request cancellation itself: BoundaryTest.aCancelledRequestEndsAndFreesItsWorkerWhileTheServerStillHoldsIt |
| destroyWhileTheUserAgentIsLookedUpThrowsDestroyedAtOnceAndTheOthersWait | NEW | ParityAdsTest.destroyWhileTheUserAgentIsReadThrowsDestroyedAtOnceAndTheOthersGoOn | RacesTest.aDestroyWhileTheUserAgentIsReadSendsNothing completed the read before awaiting, so "at once" was unchecked. "One lookup for everyone" is KontextKit's cache, replaced by the test seam |
| destroyReleasesThePlayheadProvider | NEW | ParityAdsTest.destroyReleasesTheTrackedPlayer | Android: the player's listener is removed and the player dropped |
| aMemoryWarningReleasesExpiredCreativesAndKeepsValidOnes | COVERED | PartsTest.memoryPressureReleasesExpiredAdsOnly | `onTrimMemory`; invalidate drops the bridge |
| playheadComesFromTheTrackedPlayerOrIsZero | NEW | ParityAdsTest.thePlayheadIsTheTrackedPlayersClampedOrZero | Long millis: no NaN/infinity; negative, huge, throwing player, untracked, replaced |
| aTrackedPlayerIsHeldWeakly | N/A | | intentional: `VideoAdsPlayer` is an adapter the app builds (`track(Media3Player(exo))`); held weakly it would be collected at once. Held until `destroy()`/next `track` (KDoc). See question 2 |

## VideoAdLoadingTests (25)

| Swift test | Status | Kotlin test | Note |
|---|---|---|---|
| anAdGivesTheFactsAndRendersNothing | NEW | ParityAdsTest.anAdGivesItsFactsAndRendersNothingBeforeLoad | payload facts also PayloadsTest.anOverlayDecodesWithEveryFact |
| linearFactsAreInSeconds | COVERED | PayloadsTest.aLinearAdHasItsDurationAndSkipOffset | milliseconds on Android (`durationMillis`) |
| loadMakesTheAdReadyAndFiresLoaded | COVERED | VideoAdLifecycleTest.loadWaitsForThePageAndAnotherLoadJoinsIt | isLoaded, isReady, events == ["loaded"] |
| aSecondLoadReturnsAtOnce | NEW | ParityAdsTest.aReServedAdIsTheSameObjectStillLoaded | second load returns without a dispatch, same bridge |
| loadsAtOnceShareOneRender | COVERED | VideoAdLifecycleTest.loadWaitsForThePageAndAnotherLoadJoinsIt | |
| showBeforeLoadThrowsNotLoaded | NEW | ParityAdsTest.showBeforeLoadThrowsNotLoadedAndLeavesTheAdUsable | `NotLoaded` itself: VideoAdLifecycleTest.showRefusesAnAdNotLoaded |
| loadsAtOnceShareTheSameError | NEW | ParityAdsTest.loadsAtOnceShareTheSameError | |
| cancellingALoadStopsOnlyItsWaitingTheLoadContinues | NEW | ParityAdsTest.cancellingALoadStopsOnlyItsWaitingTheLoadGoesOn | |
| aFailingCreativeThrowsItsCodeFiresErrorAndDropsTheAd | NEW | ParityAdsTest.aFailingCreativeThrowsItsCodeFiresErrorAndIsNotHeld | |
| aCreativeThatNeverLoadsThrowsTheGeneralCode | COVERED | VideoAdLifecycleTest.aPageThatNeverLoadsFailsWithinTheLoadTimeout | `CreativeFailed(500)` |
| theLoadTimeoutCountsOnlyActiveTime | COVERED | FrameMessagesTest.theLoadTimeoutCountsOnlyTimeInFrontAndSparesALoadedPage | |
| aCreativeThatDiesAfterLoadingMakesShowThrowWithoutADelegateCall | COVERED | VideoAdLifecycleTest.aCreativeThatDiesBeforeShowEndsTheAdAndShowSaysSo; ReentryTest.anAdEndedBeforeShowIgnoresItsPage | |
| expiryCountsFromTheFirstReceiptOnTheSDKsClock | NEW | ParityAdsTest.expiryCountsFromTheFirstReceiptOnTheSdksClock | the monotonic sleep-counting clock (`elapsedRealtime`) is not testable in Robolectric |
| anExpiredAdThrowsAtLoad | NEW | ParityAdsTest.anExpiredAdThrowsAtLoadAndRendersNothing | |
| anAdThatExpiresDuringTheLoadDropsTheResult | NEW | ParityAdsTest.anAdThatExpiresDuringTheLoadDropsTheResult | |
| aLoadedAdThatExpiredThrowsAtShow | COVERED | VideoAdsTest.anExpiredAdIsDroppedSilently; EdgesTest.anAdIsReadyOnlyLoadedUnshownAndValid | `Invalidated(Expired)` at show, isReady false |
| rejectBeforeLoadFiresNotUsedAndLoadThrows | NEW | ParityAdsTest.rejectBeforeLoadFiresNotUsedAndLoadThrowsAndTheAdIsNotHeld | |
| rejectOutbidCarriesTheWinningPriceAndTheWinner | NEW | ParityAdsTest.rejectOutbidCarriesTheWinningPriceAndTheWinnerBothOptional | encoding: TrackerTest.anUnknownMacroStaysEncodedAndTheNotUsedReasonIsFilled |
| rejectDuringTheLoadCancelsItAndReleasesTheWebView | NEW | ParityAdsTest.rejectDuringTheLoadEndsItAndReleasesTheWebView | |
| rejectAfterLoadMakesShowThrow | NEW | ParityAdsTest.rejectAfterLoadMakesShowThrow | |
| rejectAfterShowThrowsAlreadyShown | COVERED | VideoAdLifecycleTest.showRefusesASecondShowAndASecondAdOnScreen | `first.reject()` → AlreadyShown |
| rejectTwiceThrowsAndSendsOneNotice | COVERED | VideoAdsTest.rejectTellsTheServerWhyAndTheIdIsNeverTakenBack | |
| rejectAnExpiredAdThrowsWithoutANotice | NEW | ParityAdsTest.rejectAnExpiredAdThrowsWithoutANotice | |
| anAdOfADestroyedVideoAdsThrowsDestroyed | NEW | ParityAdsTest.anAdOfADestroyedVideoAdsThrowsDestroyedAtLoadAndReject | |
| destroyDuringTheLoadThrowsDestroyed | COVERED | VideoAdLifecycleTest.aLoadInFlightThrowsDestroyedWhenItsOwnerIsDestroyed | |

## KontextVideoTests (24)

| Swift test | Status | Kotlin test | Note |
|---|---|---|---|
| startsNotInitialized | NEW | ParityAdsTest.startsNotInitialized | |
| initializeStoresTheConfigurationAndBecomesEnabled | NEW | ParityAdsTest.initializeSendsTheTokenAndTheInstallIdAndTellsTheListener | token was in VideoAdsTest.initializeAsksConfigAndEnables; new: installId, stored configuration |
| fireAndForgetFormReportsThroughTheCompletion | NEW | ParityAdsTest.initializeSendsTheTokenAndTheInstallIdAndTellsTheListener | `Initializing` at once, listener told `Enabled` |
| aResetDuringInitializeIsNotUndoneByTheCancelledRequest | NEW | ParityAdsTest.aResetDuringInitializeIsNotUndoneByTheCancelledRequest | |
| anUnusableConfigurationStopsADebugBuild | NEW | ParityAdsTest.anUnusableConfigurationAssertsAtInitialize | the problem list: LogicTest.theConfigurationSaysWhatIsWrong |
| anEmptyUserIdStopsADebugBuild | COVERED | PartsTest.theFacadeHoldsWhatTheAppSets; LogicTest.emptyUserFieldsAreLeftOutAndNeverLogged | |
| secondInitializeIsIgnored | NEW | ParityAdsTest.aSecondInitializeIsIgnoredAndTellsNoListener | configuration kept was in PartsTest.aSecondInitializeIsIgnored |
| initializeAppliesTheLogLevel | NEW | ParityAdsTest.initializeAppliesTheLogLevel | |
| disabledByInitIsFinalForTheLaunch | NEW | ParityAdsTest.aDisabledConfigIsFinalForTheLaunch | |
| aFailedInitIsFailedAfterOneAttempt | NEW | ParityAdsTest.aFailedConfigIsOneAttemptAndEveryRequestTriesOnceMore | |
| waitUntilEnabledWaitsForInitInFlight | NEW | ParityAdsTest.formatAndAwaitInitializationWaitForConfigInFlight | Kotlin's public wait is `awaitInitialization()`; `Sdk.waitUntilEnabled` through format() |
| aFailedInitIsRetriedByTheNextRequestAtOnce | COVERED | VideoAdsTest.aFailedConfigIsTriedAgainByTheNextFormat | |
| everyRequestRetriesAFailedInitAgain | NEW | ParityAdsTest.aFailedConfigIsOneAttemptAndEveryRequestTriesOnceMore | |
| aLoadRetriesADueInit | COVERED | VideoAdsTest.aFailedConfigIsTriedAgainByTheNextFormat | the retry is format()'s, as in Swift |
| theCompletionRunsExactlyOnceForEveryOutcome | NEW | ParityAdsTest.theListenerIsToldExactlyOnceForEveryOutcome | |
| aCancelledWaiterStopsWaitingForConfigAndTheOthersShareOneConfig | NEW | ParityAdsTest.destroyWhileWaitingForConfigThrowsDestroyedAtOnceAndTheOthersShareOneConfig | Kotlin has no public waiter to cancel; the cancelled waiter is the destroyed VideoAds's request |
| theUserAgentIsLookedUpOnlyOnceTheServerEnabledTheSDK | NEW | ParityAdsTest.theUserAgentIsReadOnlyOnceTheServerEnabledTheSdk | |
| disableFromAnAdsResponseIsTheSameKillSwitch | COVERED | PartsTest.formatWithACallbackDeliversTheError; VideoAdLifecycleTest.showRefusesAfterTheSdkWasSwitchedOff | `Sdk.disable` → Disabled |
| reportDebugEnablesTheReporter | NEW | ParityAdsTest.reportDebugInTheConfigEnablesTheDebugReporter | parsing: PayloadsTest.configDefaultsTheReportsToOff |
| videoAdsBeforeInitializeAsserts | NEW | ParityAdsTest.aVideoAdsBeforeInitializeAssertsAndOnceInitializedNothingDoes | |
| requireInitializedIsQuietOnceInitialized | NEW | ParityAdsTest.aVideoAdsBeforeInitializeAssertsAndOnceInitializedNothingDoes | |
| targetingSettersAreStoredAndCompared | COVERED | PartsTest.theFacadeHoldsWhatTheAppSets | |
| installIdIsStableAndAUUID | NEW | ParityAdsTest.initializeSendsTheTokenAndTheInstallIdAndTellsTheListener | KontextKit's id; VERSION non-empty in PartsTest.theFacadeHoldsWhatTheAppSets |
| configRequestIsSentOnceEvenWhenTheFailureMayClear | NEW | ParityAdsTest.configIsSentOnceWhateverTheFailure | |

## IntegrationTests (15)

| Swift test | Status | Kotlin test | Note |
|---|---|---|---|
| pauseAdFromLoadToDismiss | COVERED | VideoAdLifecycleTest.anImageOverlayRecordsItsImpressionAtShow, dismissEndsItAtOnceWithItsViewDuration; ParityAdsTest.theWholeLoopPrepareEarlyShowAtThePauseRejectWhenOutbid | the end-to-end path (format → load → show → dismiss) is in the NEW loop test; cph macro: TrackerTest.theMacrosAreFilledAndTheContentPlayheadReadOnce |
| pauseAdClosedByTheViewer | COVERED | VideoAdLifecycleTest.theOverlaysCloseControlEndsItAsClosed | |
| clickOpensTheURLOnlyAfterATouch | COVERED | VideoAdLifecycleTest.aClickAfterATouchFiresItsBeaconOpensTheTargetAndTellsTheListener; FrameMessagesTest.aClickNeedsAnAllowedUrlAndOneTouchEach | |
| aPageNavigatingAwayIsAClickOnlyAfterATouchAndNeverLeavesTheWebView | COVERED | FrameBridgeClientTest.navigationsGoWhereTheStateAllows; FrameMessagesTest.anInDocumentMoveStaysAndAnyOtherNavigationIsDecidedByOrigin | rendering agent's area |
| aShownPageCannotReloadOrReplaceItselfButMayChangeItsFragment | COVERED | AndroidPartsTest.onceReadyThePageMayNotReplaceItself; FrameMessagesTest.anInDocumentMoveStaysAndAnyOtherNavigationIsDecidedByOrigin | |
| aPagesDialogsAreAnsweredAtOnceAsDismissed | COVERED (weaker) | FrameBridgeClientTest.dialogsAreAnsweredAsDismissedAndPermissionsDenied | calls the WebChromeClient callbacks directly; Swift runs alert, confirm and prompt in the page |
| linearPlaysToCompletion | COVERED | VideoAdLifecycleTest.aLinearAdPlaysThroughWithEveryBeaconAndClosesFromItsEndCard | |
| linearSkipped | COVERED | VideoAdLifecycleTest.aLinearAdClosedBeforeTheEndIsSkipped | |
| linearThatFailsDuringPlayback | COVERED | VideoAdLifecycleTest.aCreativeThatBreaksOnScreenFailsThenEnds; FrameMessagesTest.aVideoPageFailsWithItsOwnCodesBeforeAndAfterShow | lifecycle agent's area |
| destroyWithAnAdOnScreenEndsItAndFreesEverything | NEW | ParityAdsTest.destroyEndsEverythingWithOneNoticePerHeldAd | DESTROYED, container emptied, format() Destroyed |
| expiredAdCannotBeShownAndTheNextFormatGetsANewBid | NEW | ParityAdsTest.anExpiredAdIsDroppedSilentlyBeforeTheNextRequest | show refused: VideoAdsTest.anExpiredAdIsDroppedSilently |
| theWholeLoopPrepareEarlyShowAtThePauseRejectWhenOutbid | NEW | ParityAdsTest.theWholeLoopPrepareEarlyShowAtThePauseRejectWhenOutbid | |
| movingOnToTheNextEpisodeDestroysThePreviousAdsOnScreen | NEW | ParityAdsTest.destroyEndsEverythingWithOneNoticePerHeldAd | same rule: destroy ends the ad on screen and empties the container |
| prefetchForTheNextEpisodeUsesContentTimeZero | NEW | ParityAdsTest.aPrefetchForTheNextEpisodeUsesContentTimeZero | |
| theBridgeLivesWhileTheBidIsHeldAndIsReleasedWhenItEnds | NEW | ParityAdsTest.theWebViewLivesWhileTheAdIsHeldAndGoesWhenItEnds | |

## Mutation checks

Each mutation was applied to `video/src/main`, the suite run, and the source restored (`git checkout -- video/src/main`). They ran in four groups; within a group each listed test failed on the assertion guarding its own rule. The script is `ads-mut.py` next to this file.

| Mutation | Change | Test that failed |
|---|---|---|
| A1 | `sessionId` a constant | everyVideoAdsHasItsOwnSession |
| A2 | `held` lists only the first held ad | formatAsksForAllFormats…, otherFormatsWaitForTheirOwnCall |
| A3 | `labels` sent even when empty | labelsGoWithEveryRequestAndNoneAreLeftOut |
| A4 | one warning per dropped label | labelsThatBreakTheRules… |
| A5 | request playhead fixed at 0 | thePlayerIsTheScreenAndThePlayheadIsReadAtRequestTime |
| A5b | untracked playhead reads 80 000 | aPrefetchForTheNextEpisodeUsesContentTimeZero |
| A6 | a re-served id builds a new VideoAd | aReServedAdIsTheSameObjectStillLoaded, aReServedPayloadIsIgnored, theWebViewLivesWhileTheAdIsHeld… |
| A7 | a replaced ad invalidated as Rejected | aNewIdReplacesTheHeldAd… |
| A8 | no-fill keeps the held ad | aNoFillDropsTheHeldAd… |
| A9 | expired ads not dropped before the request | anExpiredAdIsDroppedSilentlyBeforeTheNextRequest |
| A10 | re-served id kept even when expired | aReServedIdThatExpiredDuringTheRequestNeverComesBack |
| A11 | a failed request stays shared | aFailedRequestLeavesNothingBehind… (run alone) |
| A12 | the ads request retried once on failure | serverErrorsAreRequestErrorsNeverRetried |
| A13 | left-out metadata asserts | anEmptyIdOrLanguageAssertsAndBadMetadataDoesNot |
| A14 | no /config retry before a request | aFailedConfigIsTriedAgainByFormat…, aFailedConfigIsOneAttemptAndEveryRequestTriesOnceMore |
| A15 | destroy does not map the cancellation to Destroyed | destroyWhileWaitingForConfig…, destroyWhileTheUserAgentIsRead… |
| A17 | a disabled ads response does not switch the SDK off | aDisabledResponseSwitchesTheSdkOff…, aKillSwitchDuringALoad… |
| A18 | no usability check after a failed request | switchedOffWhileAFailingRequestWasOut… |
| A19 | every format() starts its own request | callsForDifferentFormatsWhileARequestRunsShareIt |
| A20 | spent ids accepted again | anAdRejectedWhileARequestRuns…, anAdShownWhileARequestRuns… |
| A21 | destroy leaves the ad on screen; notifies expired ads | destroyEndsEverything…, destroyNotifiesValidHeldAdsOnly…, theWholeLoop… |
| A22 | destroy keeps the tracked player | destroyReleasesTheTrackedPlayer |
| A23 | playhead not clamped | thePlayheadIsTheTrackedPlayersClampedOrZero |
| A24 | the bridge made at construction | anAdGivesItsFactsAndRendersNothingBeforeLoad |
| B1 | no early return for a loaded ad | aReServedAdIsTheSameObjectStillLoaded |
| B2 | NotLoaded invalidates the ad | showBeforeLoadThrowsNotLoadedAndLeavesTheAdUsable |
| B3 | every load() starts its own load | loadsAtOnceShareTheSameError |
| B4 | a failed creative not invalidated | aFailingCreativeThrowsItsCodeFiresErrorAndIsNotHeld |
| B5 | a cancelled caller cancels the load and drops the page | cancellingALoadStopsOnlyItsWaitingTheLoadGoesOn |
| B6 | expiry `>` instead of `>=` | expiryCountsFromTheFirstReceiptOnTheSdksClock |
| B7 | expiry ignored for an unloaded ad | anExpiredAdThrowsAtLoadAndRendersNothing |
| B8 | no check after the page loaded | anAdThatExpiresDuringTheLoadDropsTheResult |
| B9 | NotUsed reported as outbid; winner dropped | rejectBeforeLoadFiresNotUsed…, rejectOutbidCarries… |
| B10 | invalidate keeps the bridge | rejectDuringTheLoadEndsItAndReleasesTheWebView, theWebViewLives… |
| B11 | reject does not invalidate | rejectAfterLoadMakesShowThrow |
| B12 | reject of an expired ad fires notUsed | rejectAnExpiredAdThrowsWithoutANotice |
| B13 | Destroyed reported as Invalidated | anAdOfADestroyedVideoAdsThrowsDestroyedAtLoadAndReject |
| C1 | initial/reset state Failed | startsNotInitialized |
| C2 | no Initializing state | initializeSendsTheTokenAndTheInstallId… |
| C3 | installId replaced | initializeSendsTheTokenAndTheInstallId… |
| C4 | listener told after a cancelled init | aResetDuringInitializeIsNotUndone… |
| C5 | no assertion for a bad configuration | anUnusableConfigurationAssertsAtInitialize |
| C6 | ignored initialize tells its listener | aSecondInitializeIsIgnoredAndTellsNoListener |
| C7 | log level not applied | initializeAppliesTheLogLevel |
| C8 | a disabled SDK retries /config | aDisabledConfigIsFinalForTheLaunch |
| C9 | /config retried once | configIsSentOnceWhateverTheFailure |
| C10 | the retry tells the initialize listener again | theListenerIsToldExactlyOnceForEveryOutcome |
| C11 | user agent read before the /config answer | theUserAgentIsReadOnlyOnceTheServerEnabledTheSdk |
| C12 | reportDebug ignored | reportDebugInTheConfigEnablesTheDebugReporter |
| C13 | no assertion before initialize | aVideoAdsBeforeInitializeAssertsAndOnceInitializedNothingDoes |
| M1 | VideoAds requests at creation | creatingAVideoAdsSendsNothing |
| W1 | a shown ad's id not marked spent | anAdShownWhileARequestRuns… |

## Source fixes

None: every Swift rule in this area holds in Kotlin.

## Questions

1. Releasing a `VideoAds` without `destroy()` (Swift: deinit warns, destroys, sends `notUsed(destroyed)` for held ads). Kotlin has no deterministic deinit; a collected owner makes its ads throw `Destroyed`, but the held bids never get their `notUsed`, and nothing warns. Accept as a platform difference (doc: call `destroy()`), or add something, e.g. a lifecycle-owner overload?
2. `track(player)` holds the `VideoAdsPlayer` strongly (Swift holds `AVPlayer` weakly). Intentional, I think: the Android player is an adapter object the app builds, which a weak reference would lose. It is released by `destroy()`/the next `track`. Worth a "platform forces it" comment in `ContentPlayback`?
3. With `SocketPolicy.DISCONNECT_AT_START` the stub records exactly one /config even when the SDK was mutated to retry it (the 503/429/400 cases did catch that retry). A likely cause: `HttpURLConnection` retries a POST by itself after a connection reset (`sun.net.http.retryPost`, on by default on the JVM; Android's OkHttp-backed connection retries on connection failure too). If so, a reset /config or ads request can reach the server twice without the SDK asking. That belongs to the network/ApiClient area; flagged here, not changed.

## Parity: content following, frame bridge, viewability, active-time clock

Kotlin test files: `PCT` = `video/src/test/kotlin/so/kontext/video/ParityContentTest.kt`,
`PRT` = `video/src/test/kotlin/so/kontext/video/internal/ParityRenderingTest.kt`.

## Core/VideoAdContentTests.swift (34)

| Swift test | Status | Kotlin test | Note |
|---|---|---|---|
| anOverlayEndsAsInterruptedWhenTheContentPauses | NEW | PCT.aPauseReadOnATickEndsTheOverlayAsInterruptedAndFreesTheScreen | GapsTest.aPauseFromTheListener… only checked hasEnded on a hand-called tick; this one uses real ticks and checks the reason, the container, isCompleted and adOnScreen |
| aPublisherEndsTheOverlayWithoutWaitingForATick | COVERED | GapsTest.aPauseFromTheListenerEndsTheOverlayAndAPlayerThatStoppedOnATickToo | the listener's onIsPlayingChanged(false) gives reasons == [INTERRUPTED] with no tick |
| showOverPausedContentThrowsAndTheAdStaysReady | NEW | PCT.showOverPausedContentThrowsWithNothingHeardAndWorksOnceItPlays | VideoAdLifecycleTest.showRefusesAnOverlayOverPausedContent checks the throw and isReady; this adds no events, an empty container, and a show that works after a resume |
| aPauseAdShownRightAfterThePauseFindsTheScreenFree | COVERED | GapsTest.anOverlayOverStoppedContentEndsBeforeAnotherAdShows; ReentryTest.anOverlayOverAPlayerThatStoppedEndsAtTheNextShowOfAnotherAd | reasons == [INTERRUPTED] and the pause ad wasShown |
| aPlayingOverlayStillBlocksAnotherAd | NEW | PCT.aPlayingTrackedOverlayStillBlocksAnotherAd | the existing AnotherAdShowing test has no tracked player |
| theDurationCountsSecondsOfVideoNotSeeksOrStalls | NEW | PCT.theDurationCountsVideoTimeNotStallsSeeksOrWallTime | LogicTest covers ContentProgress on its own; this one goes end to end with real ticks: a stall, a seek, no wall-time expiry, COMPLETED |
| anAVPlayerPauseEndsTheOverlayInTheSameTurnAndAnotherItemToo | COVERED | BoundaryTest.aPauseAndAResumeInOneEventBatchStillEndTheOverlay; BoundaryTest.anotherMediaItemEndsTheOverlayWhileStillPlaying | the Media3 versions: a pause ends it in the same call, and another media item ends it |
| theEndOfTheItemEndsTheOverlayEvenWhenTheRateStays | NEW | PCT.theEndOfAMedia3ItemEndsTheOverlayWhilePlayWhenReadyStays | Media3: STATE_ENDED while playWhenReady stays true |
| aQueuedCallbackOfAPlayerTrackedBeforeEndsNothing | NEW | PCT.aCallbackOfAPlayerTrackedBeforeEndsNothing | a call from another thread queued before the re-track, and direct calls after it |
| aPauseWhileTheOverlayIsAttachedEndsItBeforeAnImpression | NEW | PCT.aPauseFromTheListenerWhileTheHostIsAttachedEndsTheOverlayBeforeAnImpression | |
| aPauseNoCallbackReportedDuringTheAttachEndsItBeforeAnImpression | NEW | PCT.aPauseNoListenerReportedDuringTheAttachEndsItBeforeAnImpression | |
| trackingAnotherPlayerEndsTheOverlayThatFollowedTheFirst | NEW | PCT.trackingAnotherPlayerEndsTheOverlayThatFollowedTheFirst | AndroidPartsTest checks ContentChanged inside ContentPlayback only; this goes through VideoAd and checks the ticks stop |
| aPlayerThatIsGoneEndsTheOverlayAndRefusesTheNext | NEW | PCT.aPlayerThatIsGoneEndsTheOverlayAndRefusesTheNext | "gone" is a weakly held adapter target that was cleared (the SDK holds the adapter; adapters hold players weakly) |
| aShortSeekCountsNothingAndALateSampleOfRealPlaybackCounts | NEW | PCT.aShortSeekCountsNothingAndALateSampleOfRealPlaybackCounts | |
| aPublishedPauseEndsTheOverlayEvenIfPlaybackResumesBeforeDelivery | NEW | PCT.aPauseReportedOffTheMainThreadEndsTheOverlayEvenIfPlaybackResumedBeforeDelivery | BoundaryTest's batched pause covers the adapter side; this covers a queued delivery |
| anOverlaysDidEndThatDestroysTheIncomingAdsOwnerStopsItsShow | NEW | PCT.anOverlaysEndThatDestroysTheIncomingAdsOwnerStopsItsShow | |
| aPlayingGetterThatDestroysTheOwnerStopsTheShow | NEW | PCT.aPlayingReadThatDestroysTheOwnerStopsTheShow | |
| anAdShownAndEndedInsideTheHandoverIsNotReservedAgain | NEW | PCT.anAdShownAndEndedInsideTheHandoverIsNotReservedAgain | |
| aDestroyInTheOverlaysDidEndWhileTrackingAnotherPlayerLeavesNothingSubscribed | NEW | PCT.aDestroyInTheOverlaysEndWhileTrackingAnotherPlayerLeavesNothingListening | |
| aTrackInTheOverlaysDidEndWinsOverTheTrackThatEndedIt | NEW | PCT.aTrackInTheOverlaysEndWinsOverTheTrackThatEndedIt | |
| aQueuedEndOfTheItemBeforeEndsNothingOnTheNextItem | N/A | — | an AVPlayerItem end notification posted from another thread for an item that is gone. Media3 calls its listeners on the player's application looper (the adapter requires the main looper), in order, and has no per-item notification object. Callbacks from a player tracked before are covered by aCallbackOfAPlayerTrackedBeforeEndsNothing |
| anEndedItemRefusesTheNextOverlayUntilItPlaysAgain | NEW | PCT.anEndedMedia3ItemRefusesTheNextOverlayUntilItPlaysAgain | Media3: STATE_ENDED; a pause and play keep refusing; STATE_READY lets it show |
| videoPlayedWhileTheSceneWasInactiveCountsNothingOnReturn | COVERED | ReentryTest.anOverlaysWindowThatComesBackDoesNotCountWhatPlayedWhileAway | elapsed stays 1000 while the window is hidden, and counting resumes from the new position |
| fasterPlaybackCountsItsVideoSeconds | NEW | PCT.fasterPlaybackCountsItsVideoMilliseconds | LogicTest covered 4× with no speed given; this adds a known 8× and a seek at 1× |
| aDestroyWhileThePublisherIsReadLeavesNoSubscription | NEW | PCT.aDestroyBeforeTheListenerIsKeptLeavesNothingListening | Kotlin has one seam, addListener; the hook runs before the listener is stored |
| aDestroyWhileSubscribingLeavesNoSubscription | NEW | PCT.aDestroyOnceTheListenerIsKeptLeavesNothingListening | the hook runs after the listener is stored |
| aTrackWhileThePublisherIsReadKeepsTheLastPlayersSubscription | NEW | PCT.aTrackWhileTheListenerIsAddedKeepsTheLastPlayersListener | |
| aDestroyInTheOldSubscriptionsCancellationLeavesNothingTracked | COVERED | AndroidPartsTest.aStopInTheOldListenersRemovalLeavesNothingTracked | stop() is what destroy() runs; !isTracking and next.listeners is empty |
| aTrackInTheOldSubscriptionsCancellationWins | COVERED | AndroidPartsTest.aTrackInTheOldListenersRemovalWinsAndStillHandsTheOverlayOver | playhead 77, last has 1 listener, middle has none |
| aTrackInTheOldSubscriptionsCancellationEndsTheOverlayOnScreen | COVERED | AndroidPartsTest.aTrackInTheOldListenersRemovalWinsAndStillHandsTheOverlayOver | "the overlay hears the change once": ContentChanged is delivered exactly once |
| aTrackInTheCancellationThatDestroyRunsTracksNothing | NEW | PCT.aTrackInTheRemovalThatDestroyRunsTracksNothing | |
| trackAfterDestroyDoesNothing | COVERED | PartsTest.aVideoAdsWithoutAnIdAsksAndAfterDestroyRefusesEverything | track after destroy: !playback.isTracking |
| withoutATrackedPlayerTheOverlayIgnoresPauses | COVERED | AndroidPartsTest.withoutAPlayerTheContentCountsAsPlayingAndAGoneOneAsPaused; VideoAdLifecycleTest.anOverlayWithoutAPlayerEndsWhenItsDurationElapses | with no player isPlaying is true, and the overlay runs on its own time |
| destroyAndReleaseLetGoOfThePlayerAndItsObservers | NEW (partial) | PCT.destroyAndAnotherTrackLetGoOfThePlayersListener | covers the destroy and re-track halves. The "released without destroy" half is N/A on the JVM: there is no deinit, and VideoAds stays registered for component callbacks until destroy(), so destroy() is required. See the open question |

## Rendering/FrameBridgeTests.swift (50)

| Swift test | Status | Kotlin test | Note |
|---|---|---|---|
| loadedMovesLoadingToLoaded | COVERED | AndroidPartsTest.theBridgeAcceptsOnlyItsOwnPageAndOrder | state LOADED, events == [Loaded] |
| messagesWithAnotherAdIdAreDropped | COVERED | FrameMessagesTest.messagesFromAnywhereButThePageItselfAreDropped | ad-2 is dropped |
| malformedBodiesAreDropped | COVERED | FrameMessagesTest.messagesFromAnywhereButThePageItselfAreDropped | not JSON, an array, no type |
| messagesFromASubframeAreDropped | COVERED | FrameMessagesTest.messagesFromAnywhere…; AndroidPartsTest.theBridgeAcceptsOnlyItsOwnPageAndOrder | isMainFrame = false |
| messagesFromAnotherOriginAreDropped | NEW | PRT.anotherSchemeIsAnotherOrigin | another host was covered (evil.test); an http sender for an https page was not |
| originCheckTreatsPortZeroAsTheDefaultPort | NEW | PRT.theDefaultPortIsTheSameOriginAndAnyOtherIsNot | :443, :8443, localhost:5175, a custom scheme |
| unknownTypesAreDropped | COVERED | FrameMessagesTest.messagesFromAnywhere… (ad.unknown); PartsTest.theBridgeIgnoresWhatDoesNotFitItsOrder… (something.new while shown) | |
| loadedIsAcceptedOnlyWhileLoading | COVERED | FrameMessagesTest.beforeTheAdIsShownOnlyLoadedAndErrorCount | a second ad.loaded gives no event |
| showRequiresLoaded | COVERED | FrameMessagesTest.showIsIgnoredUnlessThePageIsLoaded | |
| videoEventsRequireShown | COVERED | FrameMessagesTest.beforeTheAdIsShownOnlyLoadedAndErrorCount | |
| aNonsensePlayheadFromThePageIsClamped | COVERED | FrameMessagesTest.progressTakesOnlyAWholeNumberOfMillisecondsWithinADay | a negative playhead reads as 0; 1e12 is clamped to a day |
| videoEventsCarryTheirNameAndPlayhead | NEW | PRT.everyVideoMomentCarriesItsNameAndPlayhead | every VideoEvent, plus an unknown moment |
| progressCarriesTheOffset | COVERED | FrameMessagesTest.progressTakesOnly… | offset 2000; a missing offset is dropped |
| completeDoesNotEndThePage | COVERED | PartsTest.theBridgeIgnoresWhatDoesNotFitItsOrderAndReportsABrokenEnvelopeOnce | after video.complete, hide() still sends ad.hide, which happens only in SHOWN |
| closeEndsThePageWithTheReason | NEW | PRT.closeEndsThePageAndAMissingOrUnknownReasonIsAClose | FrameMessagesTest.closeCarriesItsReason did not check ENDED or that nothing follows |
| closeWithoutAReasonOrWithAnUnknownOneReadsAsClose | NEW | PRT.closeEndsThePageAndAMissingOrUnknownReasonIsAClose | goes through receive; before, only CloseReason.from was tested |
| skipEndsThePage | COVERED | FrameMessagesTest.onScreenTheVideoMomentsArriveAndCloseOrSkipEndsThePage | |
| errorBeforeLoadedUsesTheLoadCodeWhenThePageGivesNone | COVERED | FrameMessagesTest.beforeTheAdIsShown… (500), aVideoPageFails… (400); AndroidPartsTest.aPageErrorBeforeShowFailsWithItsCode (ENDED) | |
| errorAfterShowDefaultsTo405AndKeepsThePagesCode | NEW | PRT.anErrorOnScreenKeepsThePagesCode | 405 was covered; the page's own code after show was not |
| loadTimeoutFailsWithTheGeneralCode | NEW | PRT.aVideoPagesLoadTimeoutFailsWithTheLinearGeneralCode | the existing test checked only failureCause |
| loadedBeforeTheTimeoutCancelsIt | COVERED | FrameMessagesTest.theLoadTimeoutCountsOnlyTimeInFrontAndSparesALoadedPage | |
| clickNeedsAShownPageAndARecentTouch | COVERED | FrameMessagesTest.beforeTheAdIsShown…; aClickNeedsAnAllowedUrlAndOneTouchEach | |
| clickWithoutATouchIsDropped | COVERED | FrameMessagesTest.aClickNeedsAnAllowedUrlAndOneTouchEach | |
| clickKeepsTheFallbackAndDefaultsTheArea | NEW | PRT.aClickKeepsItsFallbackAndDefaultsTheArea | |
| intentSchemeIsRefused | COVERED | FrameMessagesTest.everyRefusedSchemeIsRefusedInAnyCase; AndroidPartsTest.aClickNeedsATouchAndAnAllowedScheme | |
| aRefusedFallbackIsDroppedAndTheClickStays | COVERED | FrameMessagesTest.aClickNeedsAnAllowedUrlAndOneTouchEach | |
| schemesThatAreNotLinksAreRefused | COVERED | FrameMessagesTest.everyRefusedSchemeIsRefusedInAnyCase | market:// is the store link that opens |
| oneTouchOpensOneURL | NEW | PRT.oneTouchOpensOneUrlWhicheverWayItLeaves | a click, then a navigation and an open on the same touch |
| aRedirectWithTheExplicitDefaultPortIsTheSameOrigin | NEW | PRT.aRedirectWithTheExplicitDefaultPortIsTheSameOrigin | |
| aCommittedNavigationThatFailsWhileLoadingFailsTheAd | COVERED | FrameBridgeClientTest.aMainFrameErrorWhileLoadingFailsTheAd | |
| navigationsStoppedOnPurposeDoNotFailTheAd | N/A | — | WebKit reports a cancelled navigation as an error (-999 / 102). An Android WebView does not call onReceivedError for a load that shouldOverrideUrlLoading cancelled |
| aFailedNavigationAfterThePageIsReadyIsIgnored | COVERED | FrameBridgeClientTest.aSubframeErrorOrOneAfterLoadIsNotAFailure | loadFailed needs LOADING, so SHOWN is covered by the same guard |
| aProcessTerminationWhileLiveFailsTheAd | COVERED | FrameMessagesTest.aReplacedPageBeforeShowAndADeadRendererWhileLoading… (400); FrameBridgeClientTest.aDeadRendererFailsTheAdAndKeepsTheAppAlive (405) | |
| aTouchTooLongAgoAuthorizesNothing | NEW | PRT.aTouchTooLongAgoAuthorizesNothing | adds the navigation path and "a touch within the window still does" |
| aMalformedProgressOffsetIsDropped | COVERED | FrameMessagesTest.progressTakesOnlyAWholeNumberOfMillisecondsWithinADay | JSON cannot carry infinity |
| originsCompareWithoutCase | COVERED | FrameMessagesTest.originsSpellOutTheDefaultPort; PartsTest.onlyLinksToSomewhereMayOpen | Origin.of lowercases; http:80 is the default |
| navigationsBeforeAndAfterThePageIsReady | NEW (partial) | PRT.whileLoadingABlankPageGoesThroughAndOnceReadyTheSameFragmentStays | AndroidPartsTest.onceReady… and FrameMessagesTest.anInDocumentMove… cover the rest. Android has no reload flag in shouldOverrideUrlLoading: a reload is caught in onPageStarted (FrameBridgeClientTest.aNewDocumentAfterLoadEndsTheAd, emulator FrameNavigationTest.aReloadDoesNotSilentlyReplaceTheShownPage) |
| aParkedBridgeReleasedWithoutAnEndLeavesNothingInTheWindow | N/A | — | iOS parks a loading WKWebView in a window; the Android WebView loads unattached and is never parked |
| aProcessTerminationAfterTheEndReportsNothing | COVERED | FrameMessagesTest.onScreenTheVideoMomentsArriveAndCloseOrSkipEndsThePage | onRenderProcessGone after the end gives no event |
| openCarriesThePurposeAndIsNotAClick | COVERED | VideoAdLifecycleTest.theAdChoicesLinkFiresTheIconClickAndOpens | ad.open with purpose adChoices fires iconClick, not click |
| anOpenWithoutAPurposeIsUnknownAndAnUnfamiliarOneIsKept | NEW | PRT.anOpenCarriesAnUnfamiliarPurposeUnchanged | "unknown" was already covered in FrameMessagesTest |
| invalidURLsAreDropped | NEW | PRT.aClickWithANumberOrAnEmptyUrlIsDropped | |
| showSendsAdShowAndHideSendsAdHideOnlyWhenShown | NEW | PRT.aPageNeverShownGetsNoHide | show then hide was covered in PartsTest |
| exposureIsSentOnlyWhileShown | NEW | PRT.exposureIsSentOnlyWhileShownInTheProtocolsEnvelope | |
| envelopeIsTheProtocolsShape | NEW | PRT.exposureIsSentOnlyWhileShownInTheProtocolsEnvelope | adId, type and the payload at the top level |
| loadParksTheWebViewInTheKeyWindowAndTakeWebViewUnparksIt | N/A | — | no parking on Android |
| endingUnparksTheWebView | NEW | PRT.endingTakesTheWebViewOutOfWhereItWas | the Android version: the end takes the WebView out of its parent |
| pagesParkInTheKeyWindowUnlessToldOtherwise | N/A | — | no parking on Android |
| withoutAKeyWindowLoadStillWorks | N/A | — | no parking on Android; load never needs a window |
| webViewIsLockedDown | NEW | PRT.theWebViewIsLockedDown | the Android settings. There is no non-persistent data store (Android WebView storage isolation is out of scope by decision), and PiP/AirPlay have no Android setting |

## Rendering/ViewabilityMeterTests.swift (23)

Before this work, the meter's dwell and verdict logic had no unit tests in Kotlin; only `exposure()` geometry was tested (BoundaryTest, emulator ViewabilityTest).

| Swift test | Status | Kotlin test | Note |
|---|---|---|---|
| fullyVisibleViewBecomesViewableAfterTheDwell | NEW | PRT.aFullyVisibleViewBecomesViewableAfterTheDwell | |
| aViewInAnInactiveAppIsExposedZeroSoThePagePausesItsVideo | NEW | PRT.aViewInAWindowNotShownIsExposedZeroUntilItComesBack | on Android a backgrounded Activity's window is not VISIBLE |
| measuringGoesOnAfterTheVerdictAndTheViewLeavingTheWindowIsReported | NEW | PRT.measuringGoesOnAfterTheVerdictAndAViewLeftOutIsReportedOnEveryTickFromTheSecond | |
| viewHalfOffScreenReportsHalfExposure | NEW | PRT.halfOnScreenIsHalfExposedAndCounts | |
| viewMostlyOffScreenNeverBecomesViewable | NEW | PRT.aQuarterOnScreenNeverBecomesViewable | |
| stoppingBeforeTheDwellIsNotViewable | NEW | PRT.stoppingBeforeTheDwellIsNotViewable | |
| anAncestorThatClipsCutsTheExposure | NEW (partial) | BoundaryTest.aClippedViewIsExposedByWhatShows (the clipping); PRT.aHiddenAncestorHidesTheAd | |
| aScaledAncestorDoesNotShrinkTheShare | NEW | PRT.aScaledAncestorDoesNotShrinkTheShare | BoundaryTest scaled only the view itself |
| stopIsFinalAndStartDoesNotRestart | NEW | PRT.stopIsFinalAndStartDoesNotRestart | |
| aGapBetweenTicksRestartsTheDwell | NEW | PRT.aGapBetweenTicksRestartsTheDwell | |
| exposureIsReportedWhenThePlaceChangesAtTheSamePercentage | NEW | PRT.aNewPlaceAtTheSamePercentageIsReported | |
| exposureIsReportedOnlyWhenItChanges | NEW | PRT.exposureIsReportedOnlyWhenItChanges | |
| hiddenViewCountsAsNotExposed | NEW | PRT.aHiddenViewIsNotExposed | |
| leavingTheScreenResetsTheDwell | NEW | PRT.leavingTheScreenRestartsTheDwell | |
| viewGoneIsUndetermined | NEW | PRT.aViewThatIsGoneIsUndetermined | the meter's weak reference is cleared, so no GC is needed |
| verdictIsFinal | NEW | PRT.theVerdictIsFinal | |
| theDwellCountsToTheBoundaryExactly | NEW | PRT.theDwellCountsToTheBoundaryExactly | |
| aPauseBetweenTwoTicksRestartsTheDwell | NEW | PRT.aPauseBetweenTwoTicksRestartsTheDwell | |
| aRotatedViewHalfOffTheWindowIsMeasuredOnItsShape | NEW | PRT.aRotatedViewHalfOutsideIsMeasuredOnItsShape | Robolectric |
| aRotatedAncestorThatClipsIsMeasuredOnItsShape | COVERED | androidTest ViewabilityTest.aRotatedClippingParentLeavingFortyFivePercentIsNotViewable | emulator; the exposure is 0.45 measured on the rotated shape |
| justUnderHalfRoundsToHalfForThePageButNeverQualifies | NEW | PRT.justUnderHalfRoundsToHalfForThePageButNeverQualifies | |
| anInactiveAdWindowInAnActiveAppIsNotExposed | NEW | PRT.aViewInAWindowNotShownIsExposedZeroUntilItComesBack | the window's own visibility, not the app's |
| aZeroSizedViewOrATransparentWindowIsNotExposed | NEW | PRT.aZeroSizedViewOrATransparentWindowIsNotExposed | |

## Rendering/ActiveTimeClockTests.swift (3)

| Swift test | Status | Kotlin test | Note |
|---|---|---|---|
| anElapsedDurationIsReportedOnceWhateverTheActivityDoesAfter | NEW | PRT.anElapsedDurationIsReportedOnceWhateverTheWindowDoesAfter | ReentryTest checked "ran out once" but never toggled the window after the expiry |
| aStoppedClockNeverReportsAndKeepsItsTime | COVERED | ReentryTest.aStoppedClockNeverElapses; ReentryTest.theClockIsIdempotentAndCountsOnlyWhileVisible | a second stop returns the same value |
| inAWindowTheClockFollowsThatWindowsSceneNotTheApp | NEW | PRT.theAdsClockFollowsItsOwnWindowNotTheApp | the host view's window visibility drives the ad's clock; the app's foreground state does not |

## Mutation checks

Each mutation was applied with a literal one-match replacement (`mut/batch*.json`), both parity classes were
run, the failed tests were read from the JUnit XML, and the source was restored with `git checkout`. Mutations
ran in batches of independent changes. When a batch partner hid a failure, or a test was guarded twice, the
mutation was re-run in a later batch; the final mutation that kills each test is listed. All 64 new tests fail
under their mutation and pass on the unmutated source.

| Mutation (file) | Kills |
|---|---|
| A1 a tick-read pause does not end (VideoAdContent.contentDidChange, drop `|| !playback.isPlaying`) | aPauseReadOnATick…, aPlayerThatIsGone… |
| A2 track does not hand the overlay over (ContentPlayback.track, drop the ContentChanged delivery) | trackingAnotherPlayer…, aDestroyInTheOverlaysEnd…, aTrackInTheOverlaysEndWins… |
| A3 the told speed is ignored (ContentProgress.advance) | fasterPlaybackCountsItsVideoMilliseconds |
| A4 Media3 STATE_ENDED counts as playing (Media3Player.isActive) | theEndOfAMedia3Item…, anEndedMedia3Item… |
| A5 no check after the attach (VideoAd.showInline, drop endIfContentStopped) | aPauseNoListenerReportedDuringTheAttach… |
| A6 track after destroy is not refused (VideoAds.track) | aTrackInTheRemovalThatDestroyRunsTracksNothing |
| B1 the reported pause is ignored in favour of the state read now (contentDidChange) | aPauseReportedOffTheMainThread… |
| B2 a stale binding's call is delivered (ContentPlayback.deliver, drop the generation check) | aCallbackOfAPlayerTrackedBefore…, aTrackInTheOverlaysEndWins… (batch 4; B1 hid it in batch 2) |
| B3 SLACK_MILLIS 250 → 2000 (ContentProgress) | aShortSeekCountsNothing… |
| B4 show never refuses paused content (runAppCodeBeforeShow) | showOverPausedContent…, aPlayerThatIsGone… (refusal), anEndedMedia3Item… |
| B5 a playing overlay ends before another show (endIfContentStopped, drop the playing check) | aPlayingTrackedOverlayStillBlocksAnotherAd |
| C1m a listener added after a nested destroy or track stays (ContentPlayback.track, drop the post-add guard) | aDestroyBeforeTheListenerIsKept…, aTrackWhileTheListenerIsAdded… |
| C2m the app's code runs after the show checks (VideoAd.checkShowable order) | anOverlaysEndThatDestroysTheIncomingAdsOwner…, aPlayingReadThatDestroysTheOwner…, anAdShownAndEndedInsideTheHandover… (batch 4) |
| C4m stop keeps the player's listener (ContentPlayback.stop) | aDestroyOnceTheListenerIsKept…, destroyAndAnotherTrackLetGo… |
| C5m an overlay ignores the player until presented (contentDidChange `|| clock == null`) | aPauseFromTheListenerWhileTheHostIsAttached… (C3m, which drops `hasEnded` in presentNow, is guarded again further down presentNow and did not kill it) |
| F1 a followed overlay also runs on wall time (VideoAd.presentNow clock duration) | theDurationCountsVideoTimeNotStallsSeeksOrWallTime |
| D1 the sender is compared by host only (FrameBridge.receive) | anotherSchemeIsAnotherOrigin, theDefaultPortIsTheSameOrigin… |
| D2 a video moment loses its playhead | everyVideoMomentCarriesItsNameAndPlayhead |
| D3 close does not end the page | closeEndsThePage… |
| D4 the page's code on screen is dropped | anErrorOnScreenKeepsThePagesCode |
| D5 the load timeout uses the display code | aVideoPagesLoadTimeoutFails… |
| D6 the fallback is dropped | aClickKeepsItsFallback… |
| D7 a navigation out needs no touch | oneTouchOpensOneUrl…, aTouchTooLongAgo… |
| D8 a redirect is ours by host alone | aRedirectWithTheExplicitDefaultPort… |
| D9 a blank page is not ours while loading | whileLoadingABlankPageGoesThrough… |
| D10 an unfamiliar purpose becomes unknown | anOpenCarriesAnUnfamiliarPurposeUnchanged |
| D11 a URL without a scheme is allowed | aClickWithANumberOrAnEmptyUrlIsDropped |
| D12 hide always sends ad.hide | aPageNeverShownGetsNoHide |
| D13 exposure is sent in any state | exposureIsSentOnlyWhileShown… |
| D14 the end leaves the WebView in its parent | endingTakesTheWebViewOutOfWhereItWas |
| D15 javaScriptCanOpenWindowsAutomatically = true | theWebViewIsLockedDown |
| E1 threshold `>` instead of `>=` | halfOnScreenIsHalfExposedAndCounts |
| E2a every tick is reported (no dedupe) | exposureIsReportedOnlyWhenItChanges |
| E2b dedupe on the share alone | aNewPlaceAtTheSamePercentageIsReported |
| E3 stop decides again and a verdict may be replaced | theVerdictIsFinal |
| E4 start after stop restarts | stopIsFinalAndStartDoesNotRestart (batch 4) |
| E5 a stall keeps the dwell | aGapBetweenTicksRestartsTheDwell |
| E6 a pause (countsTime off) keeps the dwell | aPauseBetweenTwoTicksRestartsTheDwell |
| E7 the window's visibility is not read | aViewInAWindowNotShownIsExposedZero… |
| E8 transparency is not seen | aZeroSizedViewOrATransparentWindowIsNotExposed |
| E9 `isShown` is not read | aHiddenViewIsNotExposed, aHiddenAncestorHidesTheAd |
| E10 no grace tick for a detached view | measuringGoesOnAfterTheVerdict… |
| E11 a gone view is not viewable (instead of undetermined) | aViewThatIsGoneIsUndetermined |
| E12 the dwell boundary is exclusive | theDwellCountsToTheBoundaryExactly, aFullyVisibleViewBecomesViewable… |
| E13 transforms are ignored (transformToRoot) | aRotatedViewHalfOutside…, aScaledAncestorDoesNotShrinkTheShare (after it was redesigned: the first version passed with no transform) |
| E14 leaving the screen keeps the dwell | leavingTheScreenRestartsTheDwell |
| E15 stop before the dwell says viewable | stoppingBeforeTheDwellIsNotViewable, aQuarterOnScreenNeverBecomesViewable |
| F2 the rule compares the rounded share | justUnderHalfRoundsToHalfForThePageButNeverQualifies |
| K1 the expiry may restart and fire again (ActiveTimeClock) | anElapsedDurationIsReportedOnce… |
| K3 the host does not pass its window visibility on (AdHostView) | theAdsClockFollowsItsOwnWindowNotTheApp |

## Counts

- Swift tests: 110 (34 + 50 + 23 + 3)
- COVERED: 37 (content 9, bridge 26, meter 1, clock 1)
- NEW: 67 Swift tests (content 24, bridge 19, meter 22, clock 2), through 64 new Kotlin tests (24 in PCT; 40 in PRT: 17 bridge, 21 meter, 2 clock). 3 of the 67 are partial (destroyAndRelease…, navigationsBeforeAndAfter…, anAncestorThatClips…), with the rest covered or N/A as noted.
- FIXED: 0. Every Swift rule in this area holds in Kotlin.
- N/A: 6 (content 1, bridge 5), plus the release-without-destroy half of destroyAndReleaseLetGoOfThePlayerAndItsObservers.
- Emulator: none added (rotation works in Robolectric; the rotated clipping ancestor already has ViewabilityTest).

## Parity: tracker, API client, error and debug reporting, logging, environment

New tests are in `video/src/test/kotlin/so/kontext/video/internal/ParitySupportTest.kt` (PST below; since the split for detekt the tracker rows live in `ParityTrackerTest`, the API client and logger rows in `ParityNetworkTest`, the reporting rows in `ParitySupportTest`, all sharing `ParityHarness`).

| Swift test | Status | Kotlin test | Note |
|---|---|---|---|
| **TrackerTests** | | | |
| firesEachEntryOnce | COVERED | TrackerTest.anEventFiresOnceAndAClickEveryTime | impression fired twice, one beacon |
| clicksAndPlaybackTogglesFireEveryTimeWithTheirOwnFireId | NEW | PST.clicksAndPlaybackTogglesFireEveryTimeEachWithItsOwnFireId | the existing test checked click only, and no fire ids; this adds mute/pause/resume/unmute, a fire id per occurrence, and that a click-through keeps a bracket that is not a macro |
| everyFireCarriesItsOwnFireIdSharedByItsURLs | NEW | PST.everyFireCarriesItsOwnFireIdSharedByItsUrls | |
| firesTheGetWithTheSDKsTimeoutAndNoCache | COVERED + NEW | PartsTest.aBeaconIsOneGetToItsUrl (GET); PST.aBeaconIsAGetThatNoHttpCacheAnswers (no cache) | the 10 s beacon timeout is a private constant with no seam, so it is not asserted |
| selectsByPart | NEW | PST.anEntryIsSelectedByItsPart | the existing test only showed that the main click skips the end card's entry |
| selectsProgressByOffset | NEW | PST.progressIsSelectedByItsOffset | |
| progressFiresEveryCheckpointPassedOnceEachWithItsOwnFireId | COVERED + NEW | TrackerTest.progressFiresEveryPassedCheckpointInOrder; PST.progressFiresEveryPassedCheckpointOnce… | the separate fire ids are new |
| unknownEventsFireNothing | NEW | PST.anEventWithoutAnEntryFiresNothing | |
| substitutesEveryMacro | NEW | PST.everyMacroIsSubstitutedAndEncodedAsOneQueryValue | code, cph, aph, cb with exactly 8 digits, ts as ISO 8601 with ms in the device zone, ts at the moment of firing |
| playheadsAreWholeMillisecondsNeverOneShort | N/A | | Kotlin playheads are `Long` milliseconds end to end, so there is no seconds-as-Double rounding |
| aNonsensePlayheadIsClampedNotCrashed | COVERED | TrackerTest.playheadsAreFormattedAndClamped | negative and Long.MAX_VALUE; infinity and NaN cannot happen with `Long` |
| theDeprecatedContentPlayheadIsFilledLikeMediaPlayhead | NEW | PST.bothContentPlayheadNamesShareOneReadAndAnUnknownMacroIsEncodedOnce | the existing test checked one name read once; both names share one read, no `%25` |
| aPartlyEncodedPlayheadMacroIsStillFilled | NEW | PST.aPartlyEncodedPlayheadMacroIsStillFilled | |
| cacheBustingIsEightRandomDigitsPerBeacon | COVERED | TrackerTest.theMacrosAreFilled…; PST.everyMacro… (exactly 8) | |
| theContentPlayheadIsNotReadWhenNoURLAsksForIt | NEW | PST.theContentPlayheadIsNotReadWhenNoUrlAsksForIt | |
| progressAtSharedAndUnsortedOffsets… | NEW | PST.progressFiresEveryPassedCheckpointOnce… | unsorted offsets, a shared offset, one id per checkpoint |
| macrosAreURLEncoded | COVERED + NEW | TrackerTest (ph colons); PST.everyMacro… (ts colons) | |
| aWinnerAndAPriceAreEncodedAsOneQueryValue | NEW | PST.aWinnerIsEncodedAsOneQueryValue | see question 3 on the price format |
| invalidURLsAreSkippedWithoutStoppingOthers | NEW | PST.anInvalidUrlIsSkippedWithoutStoppingTheOthers | real GETs to MockWebServer |
| aFailedBeaconIsNotRetried | NEW | PST.aFailedBeaconIsNotRetried | uses a 503; see question 2 on a dropped connection |
| **ErrorReporterTests** | | | |
| reportsAreOffWhenConfigLeavesThemOutOrDisablesTheSDK | COVERED + NEW | ReportersTest.configWithoutReportsDropsThemForTheLaunch; PST.reportsAreOffWhenConfigLeavesThemOutOrDisablesTheSdk | new: through `/config` itself (field left out, `enabled: false`) |
| aFailedConfigWaitsAndLeavesOnceALaterConfigTurnsReportsOn | FIXED | PST.aFailedConfigWaitsAndLeavesWithTheIdOfItsRequest… | stage, `network`, underlying, requestId; it showed that `/config` was sent **twice** after a dropped connection (fix 3) |
| aFailedRequestSharedByThreeCallsIsOneReport | NEW | PST.aFailedRequestSharedByThreeCallsIsOneReport | |
| anAnswerThatBreaksTheContractIsADecodeFailure | NEW | PST.anAnswerThatBreaksTheContractIsADecodeFailure | |
| theBodyCarriesIdsAndNeverPersonalData | NEW | PST.theBodyCarriesIdsAndNeverPersonalData | also checks for an advertising-id canary |
| aBatchThatFailsIsRetriedUnchanged | COVERED + NEW | ReportersTest.aFailedBatchIsRetriedUnchanged (5xx); PST.aBatchWhoseRequestFailsInTransitIsRetriedUnchanged (dropped connection) | |
| a410StopsReportingForTheLaunchAndOther4xxDropTheBatch | COVERED | ReportersTest.goneStopsReportingForTheLaunch, aRefusedBatchIsDroppedNotRetried | |
| reportsBeyondTheBufferAreDroppedAndCountedOnce | COVERED | ReportersTest.beyondTheBufferTheOldestGoAndTheNextBatchSaysHowMany | |
| theCreativesFailuresAreToldApartBehindTheSameVastCode | NEW | PST.theCreativesFailuresAreToldApartBehindTheSameVastCode | a dead renderer while shown, and `ad.error` before show |
| aPageThatBreaksTheMessageContractIsReportedOncePerAd | COVERED + NEW | PartsTest.theBridgeIgnores…ReportsABrokenEnvelopeOnce; PST.aPageThatBreaksTheMessageContractIsReportedOncePerAd | new: several violations make one report, `detail: envelope`, an unknown type and a subframe are not violations |
| aPresentationUIKitNeverCompletesIsReported | NEW | PST.aLinearScreenThatNeverStartsIsReported | BoundaryTest checked the end only, not the report |
| aFailedSKAdNetworkStepCarriesTheStepAndTheErrorCodeOnly | N/A | | Android has no SKAdNetwork |
| aNoFillReasonTheSDKDoesNotKnowNeverLeavesTheDevice | NEW | PST.aNoFillReasonTheSdkDoesNotKnowNeverLeavesTheDevice | |
| longIdsAreCappedAndABatchStaysWithinItsByteBudget | COVERED + NEW | ReportersTest (byte budget, stricter: the whole body); PST.everyStringFieldIsCappedByBytes… | new: every field of a sent report is capped |
| aCharacterWithThousandsOfCombiningMarksIsCappedByBytes | COVERED + NEW | LogicTest.reportFieldsAreCutAtACodePoint; PST.everyStringFieldIsCappedByBytes… | new: one character of 5000 marks, plus the history moment's name, adId and state |
| aFailedConfigIsReportedWithTheIdOfItsRequest | NEW | PST.aFailedConfigWaitsAndLeavesWithTheIdOfItsRequest… | "one attempt" caught fix 3 |
| anExpiredHeldAdIsNotCountedAsKept | NEW | PST.anExpiredHeldAdIsNotCountedAsKept | |
| aPageThatCannotLoadCarriesTheSystemErrorsDomainAndCode | NEW | PST.aPageThatCannotLoadCarriesTheSystemsError | `underlying` is `{domain: "webview", code: -2}`, as in Swift |
| integrationMistakesAreReportedOnceTheServerAllowsIt | NEW | PST.integrationMistakesAreReportedOnceTheServerAllowsIt | |
| **DebugReporterTests** | | | |
| nothingIsSentWhileDisabled | COVERED + NEW | ReportersTest.debugEventsLeaveOnlyWhenTheServerAsked; PST.nothingIsSentWhenConfigDoesNotAsk | |
| enabledByInitEventsAreBatchedWithTheBodyShape | NEW | PST.enabledByConfigEventsAreBatchedWithTheBodyShape | |
| eventsAreCoalescedIntoOneRequestPerInterval | NEW | PST.eventsAreCoalescedIntoOneRequestPerInterval | |
| disablingDropsTheBuffer | NEW | PST.disablingDropsTheBuffer | |
| aFailedBatchIsLostQuietlyAndTheNextOneGoesOut | NEW | PST.aFailedBatchIsLostQuietlyAndTheNextOneGoesOut | |
| debugReportsCarryNoneOfTheViewersData | NEW | PST.debugReportsCarryNoneOfTheViewersData | |
| oneRequestAtATimeAndTheRestWaitForIt | NEW | PST.oneRequestAtATimeAndTheRestWaitForIt | |
| theBufferKeepsTheNewestEventsOnly | NEW | PST.theBufferKeepsTheNewestEventsOnly | |
| aNumberBeyondIntFormatsWithoutATrap | FIXED | PST.aNumberBeyondLongFormatsAsItselfWithoutATrap | 1e30 printed as 9223372036854775807 (fix 2); Kotlin's spelling stays `1.0E30`, `Infinity`, `NaN` |
| theLoadFlowReportsItsEvents | NEW | PST.theLoadFlowReportsItsEvents | |
| anEventReadsAsOneConsoleLine | COVERED | ReportersTest.debugEventsLeaveOnlyWhenTheServerAsked (line) | |
| **APIClientTests** | | | |
| postsJSONToTheV2Route | NEW | PST.postsJsonToTheV2Route | |
| aSlowAnswerIsCutAtTheDeadlineNotOnlyWhenIdle | COVERED | BoundaryTest.aTricklingAnswerHitsTheWholeCallDeadline | |
| theSameValueEncodesToTheSameBytes | N/A | | Swift sorts keys because its dictionaries have a random order per process; kotlinx JsonObject keeps insertion order, and ErrorReporter retries the frozen body |
| theSDKsSessionIsItsOwnWithADeadlineAndNoCookiesOrCache | PARTLY: COVERED + NEW | BoundaryTest (deadline); PST.noHttpCacheAnswersARequest | the deadline and no cache are tested; cookies are an accepted difference, not covered: an app that installs a global CookieHandler adds its cookies |
| clientErrorCarriesTheServersErrorBody | NEW | PST.aClientErrorCarriesTheServersErrorBody | |
| anErrorBodyTheClientCannotReadStillGivesTheStatus | COVERED + NEW | PayloadsTest.theErrorBodyIsReadLeniently; PST.anErrorBodyTheClientCannotReadStillGivesTheStatus | |
| serverErrorsAndRateLimitsAreHTTPErrorsWithTheirStatus | NEW | PST.serverErrorsAndRateLimitsAreHttpErrorsWithTheirStatus | |
| aTransportFailureIsATransportError | NEW | PST.aTransportFailureIsATransportError | |
| unexpectedJSONIsADecodingError | NEW | PST.unexpectedJsonIsADecodingError | |
| emptyBodyDecodesAsEmptyResponse | NEW | PST.anEmptyBodyIsAnEmptyAnswer | |
| cancellationSurfacesAsURLErrorCancelled | COVERED | BoundaryTest.aCancelledRequestEndsAndFreesItsWorkerWhileTheServerStillHoldsIt | in Kotlin it surfaces as CancellationException, the coroutine idiom |
| aTaskCancelledBeforePostSendsNothing | COVERED + NEW | BoundaryTest.aRequestCancelledWhileQueuedSendsNothing; PST.aCallerCancelledBeforePostSendsNothing | new: `post`'s own `ensureActive` |
| **LoggerTests** | | | |
| aDisabledLevelNeverBuildsItsMessage | NEW | PST.aDisabledLevelNeverBuildsItsMessageAndAnEnabledOneBuildsItOnce | PartsTest checked the output, not whether the message was built |
| anEnabledLevelBuildsItsMessageOnce | NEW | same | |
| aURLIsLoggedAsItsSchemeAndHostOnly | FIXED | PST.aUrlIsLoggedAsItsSchemeAndHostOnly | the port reached the log (fix 1) |
| loggerLevelIsSettable | COVERED | PartsTest.theLogShowsOnlyWhatItsLevelLets | |
| **EnvironmentTests** | | | |
| anAppWithoutScenesFindsItsWindowOnTheAppDelegate | N/A | | UIKit scenes and app delegate |
| theKeyWindowComesFromTheActiveSceneThenAnInactiveOne… | N/A | | UIKit scenes |
| aWindowIsActiveOnlyWhenTheAppAndItsOwnSceneAre | N/A | (PartsTest.startedActivitiesAreCounted) | Android's counterpart is ActivityTracker's started count plus window visibility |
| **AwaitSharedTests** | | | |
| awaitSharedGivesEveryWaiterTheSameResult… | COVERED | VAL.loadWaitsForThePageAndAnotherLoadJoinsIt, RacesTest.twoCallersShareOneRequestAndOneLeavingDoesNotStopIt | the Swift helper is N/A (a `Deferred` shares the work); the behaviour applies and is tested at the API |
| awaitSharedSharesAFailureAmongItsWaiters | COVERED | ParityAdsTest.loadsAtOnceShareTheSameError | the shared failure, at the API |
| aCallerCancelledBeforeItWaitsStopsAtOnce | COVERED | BoundaryTest.aRequestCancelledWhileQueuedSendsNothing, RacesTest.twoCallersShareOneRequestAndOneLeavingDoesNotStopIt | cancellation isolation, at the API |
| oneCancelledWaiterLeavesTheOthersWaiting | COVERED | RacesTest.twoCallersShareOneRequestAndOneLeavingDoesNotStopIt | |
| cancellationRacingCompletionResumesOnce | N/A | | a double resume of a continuation is ruled out by kotlinx.coroutines itself |

## Source fixes

1. `internal/support/Logger.kt` `describe`: the port went into the log (`https://brand.test:8443`); now it is the host only, as in Swift.
2. `internal/support/DebugReporter.kt` `describe`: a whole Double beyond Long range was clamped (`1e30` printed as `9223372036854775807`); now only whole values below 2^53 print as integers.
3. `internal/network/ApiClient.kt`: `setFixedLengthStreamingMode(bytes.size)`. Without it, HttpURLConnection silently sends a POST again when the connection drops before the answer: `/config` (and so an ads request) went out twice, which breaks the "one attempt" rule.

## Questions

1. Resolved: `underlying` is `{domain, code}`, as in Swift.
2. Beacon GETs: HttpURLConnection may resend an idempotent GET once after a dropped connection. A streaming mode does not apply to a GET with no body, so this is not fixed.
3. Resolved: `[KONTEXT_WINNINGPRICE]` is written with the shortest round-trip digits in Swift's notation; TrackerTest pins the values Swift printed.
4. Accepted difference: HttpURLConnection uses `CookieHandler.getDefault()`, so an app that installs one would send its cookies with SDK requests. Swift's session refuses cookies.

## Mutation checks (each new test failed under its mutation; the sources were then restored)

| Test (PST.) | Mutation |
|---|---|
| clicksAndPlaybackToggles… | `unmute` dropped from `Tracker.REPEATABLE` |
| everyFireCarriesItsOwnFireId… | a new fire id per entry instead of per occurrence |
| aBeaconIsAGetThatNoHttpCacheAnswers | `useCaches = true` in `Tracker.get` |
| anEntryIsSelectedByItsPart | the `entry.part != part` check removed (the test was strengthened after this mutation first survived) |
| progressIsSelectedByItsOffset | the offset check in `fire` removed |
| progressFiresEveryPassedCheckpointOnce… | `fireProgress` fires one `fire("progress")` for all due checkpoints |
| anEventWithoutAnEntryFiresNothing | an event with no entry falls back to the impression entries |
| everyMacroIsSubstituted… | `[TIMESTAMP]` without milliseconds |
| bothContentPlayheadNamesShareOneRead… | `[CONTENTPLAYHEAD]` reads the player again |
| aPartlyEncodedPlayheadMacroIsStillFilled | the `%5B`/`%5D` normalization removed |
| theContentPlayheadIsNotReadWhenNoUrlAsksForIt | the content playhead read unconditionally |
| aWinnerIsEncodedAsOneQueryValue | `[KONTEXT_WINNER]` not encoded |
| anInvalidUrlIsSkippedWithoutStoppingTheOthers | `URL(url)` parsed synchronously in `Tracker.get`, outside `runCatching` |
| aFailedBeaconIsNotRetried | a second GET after a status ≥ 400 |
| postsJsonToTheV2Route | Content-Type `text/plain` |
| noHttpCacheAnswersARequest | `useCaches = true` in `ApiClient` |
| aClientErrorCarriesTheServersErrorBody | `parseErrors` reads the wrong fields |
| anErrorBodyTheClientCannotReadStillGivesTheStatus | `ApiException.Http`'s message prints `?` for an empty item |
| serverErrorsAndRateLimitsAreHttpErrors… | 5xx thrown as `Transport` |
| aTransportFailureIsATransportError | an `IOException` mapped to `Decoding` |
| unexpectedJsonIsADecodingError | a parse failure returns `null` |
| anEmptyBodyIsAnEmptyAnswer | the blank-body check removed for `debug` |
| aCallerCancelledBeforePostSendsNothing | `ensureActive()` removed from `post` |
| aDisabledLevelNeverBuildsItsMessage… | the message built before the level check |
| aUrlIsLoggedAsItsSchemeAndHostOnly | failed before fix 1 (the port) |
| reportsAreOffWhenConfigLeavesThemOut… | `/config` calls `configure` only when `reportErrors` is true |
| aFailedConfigWaitsAndLeavesWithTheIdOfItsRequest… | failed before fix 3 (two attempts); and the report given a new requestId |
| aFailedRequestSharedByThreeCallsIsOneReport | the request failure reported twice |
| anAnswerThatBreaksTheContractIsADecodeFailure | the code always `ads.request_failed` |
| theBodyCarriesIdsAndNeverPersonalData | the user id put in the error envelope |
| aBatchWhoseRequestFailsInTransitIsRetriedUnchanged | a transport failure drops the batch |
| theCreativesFailuresAreToldApart… | the stage always `load` |
| aPageThatBreaksTheMessageContractIsReportedOncePerAd | `reportedProtocolViolation = true` removed |
| aLinearScreenThatNeverStartsIsReported | the timeout reports `ad.dismissal_timed_out` instead |
| aNoFillReasonTheSdkDoesNotKnowNeverLeavesTheDevice | the debug `reason` carries the server's free text |
| everyStringFieldIsCappedByBytes… | the history moment's adId not capped |
| anExpiredHeldAdIsNotCountedAsKept | `holdsAds = ads.values.isNotEmpty()` |
| aPageThatCannotLoadCarriesTheSystemsError | `failureUnderlying` not set |
| integrationMistakesAreReportedOnce… | the `bad_content` report removed |
| nothingIsSentWhenConfigDoesNotAsk | `DebugReporter.isEnabled = true` whatever `/config` says |
| enabledByConfigEventsAreBatchedWithTheBodyShape | `level` left out |
| eventsAreCoalescedIntoOneRequestPerInterval | at most 5 events a batch |
| disablingDropsTheBuffer | the buffer not cleared on disable |
| aFailedBatchIsLostQuietly… | a failed batch put back in the buffer |
| debugReportsCarryNoneOfTheViewersData | the user id put in the debug body |
| oneRequestAtATimeAndTheRestWaitForIt | `flushJob = null` before `flush()` |
| theBufferKeepsTheNewestEventsOnly | the newest event dropped instead of the oldest |
| aNumberBeyondLongFormatsAsItselfWithoutATrap | failed before fix 2 |
| theLoadFlowReportsItsEvents | the `ad.loaded` event removed |
