"""Pure contracts for a separate API26 maintenance proof; no execution on import."""
import hashlib
import importlib.util
import json
from pathlib import Path
import re

ROOT = Path('C:/Users/Dominik/.codex/worktrees/r27-storage-maintenance/CardVault')
MAIN = Path('C:/Users/Dominik/AndroidStudioProjects/CardVault')
HERE = ROOT / 'scripts/verification/maintenance-proof'
OUTPUT = ROOT / 'artifacts/release27-engineering-20261004/maintenance-proof'
SERIAL, AVD = 'emulator-5558', 'DomiDex_R27_Compatibility_API26'
MODEL = 'Android SDK built for x86_64'
FINGERPRINT = 'Android/sdk_gphone_x86_64/generic_x86_64:8.0.0/OSR1.180418.026/6741039:userdebug/dev-keys'
RUNNER = 'com.dominik.cardvault.MaintenanceProofRunner'
CERT = '61e8314b147e44653b8e0d169fe6caf22760c36ee47795cd8471b434166ba854'
VALIDATOR = ROOT / 'scripts/verification/android-regression/validate.py'
VALIDATOR_SHA = '7bfec99236b301a8bd9e3e42976e71fc2073553de808fd9cf01d721548b409e3'
BUILD_SECONDS = RUN_SECONDS = 1200
LOG_BYTES = 16 * 1024 * 1024
APK_PATHS = ('outputs/apk/debug/app-debug.apk', 'outputs/apk/androidTest/debug/app-debug-androidTest.apk')
CLASSES = {
    'com.dominik.cardvault.data.PriceHistoryRetentionInstrumentedTest': (
        'utcLeapBoundaryKeepsExactLastStateAndEveryOriginalWindowField',
        'scanLimitCountsNonDeletableRowsAndEachNonemptyBatchUsesOneRangeDelete',
        'interruptedPassReplayFreshPassAndHighWaterAreIdempotent',
        'guestTwoOwnersPhotosOutboxesLayoutsPricePreferencesAndOtherTablesAreByteExact',
        'outerTransactionFailureRollsBackDeletesAndInputCursorRemainsReusable',
        'cancellationAtRealDeleteRollsBackAndCancelledInputCanBeRetried',
        'cancellationAfterNestedBatchBeforeOuterCommitAlsoRollsBack',
        'activeRoomHistoryFlowAndTrackerObserveCommittedPruningWithoutManualRefresh',
        'concurrentReplaceOnEitherSideOfRetentionPreservesCorrectedAnchorAndCurrentRow',
        'emptyAndSignedPrimaryKeysExtremeCutoffsAndInvalidInputsFailSafely',
        'collectionOnlyIsOptInAndUnownedLatestNullZeroNegativeStatesRemainByteExact',
        'collectionOnlyProtectsEveryOwnerTombstonesUnsyncedExactLinksAndStoredSelections',
        'collectionProtectionIsReevaluatedAfterCommittedEntryChangesBetweenBatches',
        'collectionPolicyUsesOneDeleteWithAnUncorrelatedProtectionListAndIndexedPriceProbes',
        'collectionOnlyCancellationAndOuterRollbackPreserveOwnershipAndAllRows'),
    'com.dominik.cardvault.PriceHistoryWindowRepositoryInstrumentedTest': (
        'compactWindowKeepsPriorStateInclusiveObservationsAndIndependentFinishBases',
        'unsafeQuantityUsesRealChronologicalFallbackWithTheSameWindowContract',
        'liveWindowAndOwnerChangesRecomputeWithoutSyntheticPointsOrPersistedWrites'),
    'com.dominik.cardvault.data.sync.CardmarketImportRoomInstrumentedTest': (
        'nullTrendPreservesEveryAlternativeAndOriginalSourceTime',
        'sameSourceTimeReplacesFieldsWithoutDuplicatingPriceHistory',
        'lateSqliteFailureRollsBackAllChunksAndSuccessState',
        'contradictoryRowsCannotReplaceTheLastGoodRoomSnapshotInEitherOrder',
        'historyClockIsCommittedWithQuotesAndMissingReferenceKeepsPriorValue',
        'lateHistoryClockWriteFailureRollsBackQuotesAndSuccessfulState'),
    'com.dominik.cardvault.data.PriceHistoryMaintenanceInstrumentedTest': (
        'freshImportTicketCommitsWithPricesAndMissingClockNeverRearmsIt',
        'lateTicketFailureRollsBackPricesClockSuccessAndPreviousTicket',
        'cursorWriteFailureRollsBackRealDeleteAndBothMetadataKeys',
        'cancellationAtDeleteOrCursorSqlRollsBackAllChanges',
        'outerRollbackDoesNotLeaveDeletionOrDurableProgressAheadOfCommit',
        'fileDatabaseResumeCrossesLongProtectedPrefixWithoutRescanningIt',
        'twoCoordinatorsReadCommittedCursorInsteadOfDuplicatingAStaleBatch',
        'newImportPreservesActivePassAndOldCompletionCannotClearNewTicket',
        'damagedCursorRescansOnlyWithValidTicketAndNeverUsesStoredClockAlone',
        'realSqlCursorConflictRollsBackInsteadOfOverwritingChangedState',
        'ownershipCommittedBetweenBatchesProtectsRemainingWindowRowsAndOutbox',
        'emptyPassCompletesAndInvalidBatchSizesCannotMutateAnything',
        'completionTicketFailureRollsBackDeletionAndCursorRemovalTogether',
        'missingOrMismatchedVerifiedClockCannotAuthorizeEvenAWellFormedTicket',
        'regressedVerifiedClockPausesByteExactlyAndFreshTicketResumesOriginalCursor'),
    'com.dominik.cardvault.data.PriceHistoryStorageReclaimInstrumentedTest': (
        'realRowsDurableResumeAndOfflineVacuumReleaseOwnedFileWithoutChangingRetainedData',
        'platformDefaultRetentionReleasesRealSpaceWithoutChangingRetainedData'),
    'com.dominik.cardvault.data.local.PriceHistoryMaintenanceWorkManagerInstrumentedTest': (
        'failedPrerequisiteReallyPoisonsAlreadyBlockedSuccessor',
        'actualPausedWorkerAllowsBlockedFreshImportSuccessor',
        'actualRetryExhaustedWorkerAllowsBlockedFreshImportSuccessor'),
}
TESTS = [cls + '#' + method for cls, methods in CLASSES.items() for method in methods]
ISOLATED_SOURCES = {
    'com.dominik.cardvault.data.PriceHistoryStorageReclaimInstrumentedTest':
        'scripts/verification/maintenance-proof/testSources/com/dominik/cardvault/data/PriceHistoryStorageReclaimInstrumentedTest.kt',
    'com.dominik.cardvault.data.local.PriceHistoryMaintenanceWorkManagerInstrumentedTest':
        'scripts/verification/maintenance-proof/testSources/com/dominik/cardvault/data/local/PriceHistoryMaintenanceWorkManagerInstrumentedTest.kt',
}
SOURCES = tuple(ISOLATED_SOURCES.get(cls, 'app/src/androidTest/java/' + cls.replace('.', '/') + '.kt')
                for cls in CLASSES)
PROFILE_KEYS = {'schema', 'serial', 'avd', 'model', 'fingerprint', 'api', 'abi', 'imageRevision',
                'sqliteVersion', 'bootId', 'avdIniSha256', 'avdConfigSha256', 'imagePackageSha256',
                'imageSourcePropertiesSha256', 'ordinaryPackages'}

def require(value, message):
    if not value:
        raise ValueError(message)

def digest(path):
    result = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            result.update(block)
    return result.hexdigest()

def no_links(path):
    require(not any(p.is_symlink() or (hasattr(p, 'is_junction') and p.is_junction())
                    for p in (Path(path), *Path(path).parents)), 'No linked proof paths')

def identity(directory):
    no_links(directory)
    path = Path(directory).absolute().resolve()
    require(re.fullmatch('[a-f0-9]{32}', path.name) is not None and path == OUTPUT / path.name,
            'Exact UUID maintenance build directory required')
    return path.name, 'com.dominik.cardvault.maintenanceproof.r' + path.name

def validator():
    no_links(VALIDATOR)
    require(digest(VALIDATOR) == VALIDATOR_SHA, 'Reviewed pure validator changed')
    spec = importlib.util.spec_from_file_location('maintenance_pure_validator', VALIDATOR)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module

def read_json(path, maximum=16 * 1024 * 1024):
    no_links(path)
    require(Path(path).is_file() and Path(path).stat().st_size <= maximum, 'Missing or oversized receipt')
    def unique(pairs):
        result = {}
        for key, value in pairs:
            require(key not in result, 'Duplicate JSON key')
            result[key] = value
        return result
    return json.loads(Path(path).read_text(encoding='utf-8'), object_pairs_hook=unique)

def validate_profile(value):
    require(isinstance(value, dict) and set(value) == PROFILE_KEYS, 'Exact fresh reviewed profile required')
    for key, expected in {'schema': 1, 'serial': SERIAL, 'avd': AVD, 'model': MODEL,
                          'fingerprint': FINGERPRINT, 'api': 26, 'abi': 'x86_64',
                          'imageRevision': 16, 'sqliteVersion': '3.18.2'}.items():
        require(type(value[key]) is type(expected) and value[key] == expected, 'Wrong fixed profile identity')
    require(isinstance(value['bootId'], str) and re.fullmatch('[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}', value['bootId']),
            'Fresh observed boot UUID required')
    for key in ('avdIniSha256', 'avdConfigSha256', 'imagePackageSha256', 'imageSourcePropertiesSha256'):
        require(isinstance(value[key], str) and re.fullmatch('[a-f0-9]{64}', value[key]), 'Fresh metadata digest required')
    ordinary = value['ordinaryPackages']
    require(isinstance(ordinary, dict) and set(ordinary) == {'com.dominik.cardvault', 'com.dominik.cardvault.test'},
            'Explicit ordinary package inventory required')
    for hashed in ordinary.values():
        require(hashed is None or isinstance(hashed, str) and re.fullmatch('[a-f0-9]{64}', hashed), 'Invalid installed package digest')
    return value

def snapshot():
    # Do not read runtime DBs or the retained main 13GiB artifacts.
    # Protect both source trees and standard APK/AAB/mapping/v26 outputs instead.
    inputs = ('app/src', 'app/schemas', 'app/build.gradle.kts', 'build.gradle.kts', 'settings.gradle.kts',
              'gradle.properties', 'gradle', 'gradlew', 'gradlew.bat', 'app/google-services.json', 'local.properties',
              'scripts/verification')
    protected = ('app/build/outputs/apk', 'app/build/outputs/bundle', 'app/build/outputs/mapping',
                 'releases/google-play-v26-closed-alpha-2026-09-23')
    result = {}
    for label, root in (('worktree', ROOT), ('main', MAIN)):
        result[label] = {}
        for relative in (*inputs, *protected):
            path = root / relative
            no_links(path)
            require(path.exists() or relative in protected, 'Required source missing: ' + str(path))
            files = sorted(path.rglob('*')) if path.is_dir() else [path]
            result[label][relative + '/@exists'] = path.exists()
            for file in files:
                no_links(file)
                if file.is_file() and '__pycache__' not in file.parts:
                    result[label][file.relative_to(root).as_posix()] = {'bytes': file.stat().st_size, 'sha256': digest(file)}
    return result

def check_source_selection():
    require(len(TESTS) == 44 and len(set(TESTS)) == 44, 'Exact 44 methods required')
    require(len(SOURCES) == 6 and len(set(SOURCES)) == 6 and
            len({Path(source).name for source in SOURCES}) == 6, 'Six unique test sources required')
    for cls in ISOLATED_SOURCES:
        require(not (ROOT / ('app/src/androidTest/java/' + cls.replace('.', '/') + '.kt')).exists(),
                'Proof-only test must not enter ordinary instrumentation')
    for cls, source in zip(CLASSES, SOURCES):
        text = (ROOT / source).read_text(encoding='utf-8')
        actual = re.findall(r'@Test\s+fun\s+(\w+)\s*\(', text)
        require(len(actual) == len(CLASSES[cls]) and set(actual) == set(CLASSES[cls]), 'Test source/allowlist changed')
        require(not re.search(r'@Ignore|Assume\.|assumeTrue|assumeFalse', text), 'No skipped assumptions')

def validate_log(text):
    require(not re.search(r'shortMsg=|AssumptionViolated|(?:^|\n).*Exception', text), 'No hidden exception or skip')
    return validator().validate_instrumentation(text, TESTS)

def validate_manifest(tree, package, test=False):
    require(not re.search(r'E: (?:activity|activity-alias|provider|service|receiver)(?:\s|$)', tree), 'No app components')
    require('android:sharedUserId' not in tree and 'android:sharedUserLabel' not in tree, 'No shared UID')
    require('android.permission.INTERNET' not in tree and 'android.permission.CAMERA' not in tree, 'No network/camera permission')
    names = re.findall(r'android:name(?:\(0x[0-9a-fA-F]+\))?="([^"]+)"', tree)
    require(names.count('android.app.Application') == 1, 'Plain Application required')
    require(len(re.findall(r'E: instrumentation(?:\s|$)', tree)) == int(test), 'Unexpected instrumentation declaration')
    if test:
        require(names.count(RUNNER) == 1 and re.findall(r'android:targetPackage(?:\(0x[0-9a-fA-F]+\))?="([^"]+)"', tree) == [package],
                'Exact owned target/runner required')

def validate_install(text):
    lines = text.splitlines()
    require(lines and lines[-1] == 'Success' and lines.count('Success') == 1 and
            not re.search(r'Failure|Error|error|failed|INSTALL_FAILED', text), 'Install did not finish successfully')

def validate_owner(value, run, profile_hash, packages, hashes):
    require(isinstance(value, dict) and set(value).issubset({'schema', 'runId', 'profileSha256', 'installed', 'installIntent', 'uninstallIntent', 'cleaned'})
            and {'schema', 'runId', 'profileSha256', 'installed'} <= set(value), 'Malformed ownership receipt')
    require(type(value['schema']) is int and value['schema'] == 1 and value['runId'] == run and value['profileSha256'] == profile_hash, 'Wrong package owner')
    require(isinstance(value['installed'], dict) and set(value['installed']) <= set(packages), 'Foreign package ownership')
    require(all(value['installed'][p] == hashes[p] for p in value['installed']), 'Ownership APK changed')
    if 'installIntent' in value:
        require(value['installIntent'] in packages and value['installIntent'] not in value['installed'], 'Wrong uncertain install target')
    if 'uninstallIntent' in value:
        require(value['uninstallIntent'] in value['installed'] and 'installIntent' not in value, 'Wrong uncertain uninstall target')
    if 'cleaned' in value:
        require(value['cleaned'] is True and not value['installed'] and 'installIntent' not in value and 'uninstallIntent' not in value, 'Invalid cleaned state')
    return value
