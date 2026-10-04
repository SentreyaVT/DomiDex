"""Pure fail-closed contracts. This module never opens a device or starts a build."""
import hashlib
import importlib.util
from pathlib import Path
import re

ROOT = Path('C:/Users/Dominik/AndroidStudioProjects/CardVault')
HERE = ROOT / 'scripts/verification/storage-proof'
OUTPUT = ROOT / 'artifacts/release27-engineering-20261004/storage-proof'
SERIAL, AVD = 'emulator-5562', 'DomiDex_R27_Storage13GiB_API36_20261004'
CLASS, RUNNER = 'com.dominik.cardvault.PriceHistoryStorageScaleInstrumentedTest', 'com.dominik.cardvault.StorageProofRunner'
CERT = '61e8314b147e44653b8e0d169fe6caf22760c36ee47795cd8471b434166ba854'
GIB = 1024 ** 3
CONTRACT_CLASSES = {
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
        'lateHistoryClockWriteFailureRollsBackQuotesAndSuccessfulState')}
CONTRACT_TESTS = [name + '#' + method for name, methods in CONTRACT_CLASSES.items() for method in methods]
SOURCES = ('app/src/androidTest/java/com/dominik/cardvault/PriceHistoryStorageScaleInstrumentedTest.kt',
           'app/src/androidTest/java/com/dominik/cardvault/data/PriceHistoryRetentionInstrumentedTest.kt',
           'app/src/androidTest/java/com/dominik/cardvault/PriceHistoryWindowRepositoryInstrumentedTest.kt',
           'app/src/androidTest/java/com/dominik/cardvault/data/sync/CardmarketImportRoomInstrumentedTest.kt')
PHASES = {'contracts': ('CONTRACTS', 1200), 'pilot': ('pilotRealPriceRows', 1800), 'seed': ('seedThirteenGiB', 28800),
          'verify': ('verifySeededDatabase', 14400), 'prune': ('pruneFirstBatches', 1800),
          'resume': ('resumeByFreshIdempotentPass', 14400), 'reclaim': ('reclaimOwnedDatabase', 14400)}
APK_PATHS = ('outputs/apk/debug/app-debug.apk', 'outputs/apk/androidTest/debug/app-debug-androidTest.apk')
PINS = {'scripts/verification/workmanager-proof/build.py':
        '1c7df731df078d3404eb5cfa4430d7c8aeeb0c5ae8e86301c07c415f4b41452b',
        'scripts/verification/android-regression/validate.py':
        '4cf12d32ddfc56dc00d7f481e66baab33c0ddee5f196316ad3739129aae0cc1a'}

def require(condition, message):
    if not condition:
        raise ValueError(message)

def digest(file):
    h = hashlib.sha256()
    with Path(file).open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            h.update(block)
    return h.hexdigest()

def load_pinned(relative):
    file = ROOT / relative
    require(not file.is_symlink() and digest(file) == PINS[relative], 'Reviewed pure helper changed')
    spec = importlib.util.spec_from_file_location('storage_' + file.stem, file)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module

def snapshot():
    value = load_pinned('scripts/verification/workmanager-proof/build.py').snapshot()
    for file in sorted(HERE.rglob('*')):
        require(not file.is_symlink(), 'No linked storage-proof input')
        if file.is_file() and '__pycache__' not in file.parts:
            value['inputs'][file.relative_to(ROOT).as_posix()] = {'bytes': file.stat().st_size, 'sha256': digest(file)}
    return value

def identity(directory):
    original = Path(directory).absolute()
    require(not any(p.is_symlink() or (hasattr(p, 'is_junction') and p.is_junction()) for p in (original, *original.parents)),
            'No linked build path')
    directory = original.resolve()
    run = directory.name
    require(re.fullmatch('[a-f0-9]{32}', run) is not None and directory == OUTPUT / run,
            'Exact fresh storage-proof build directory required')
    require(not directory.is_symlink(), 'No linked build directory')
    return run, 'com.dominik.cardvault.storageproof.r' + run

def validate_manifest(tree, package, test=False):
    require(not re.search(r'E: (?:activity|activity-alias|provider|service|receiver)(?:\s|$)', tree),
            'No components in the isolated storage package')
    require('android:sharedUserId' not in tree and 'android:sharedUserLabel' not in tree, 'No shared UID')
    require('android.permission.INTERNET' not in tree and 'android.permission.CAMERA' not in tree, 'No network/camera')
    names = re.findall(r'android:name(?:\(0x[0-9a-fA-F]+\))?="([^"]+)"', tree)
    require(names.count('android.app.Application') == 1, 'Plain Application required')
    if test:
        require(len(re.findall(r'E: instrumentation(?:\s|$)', tree)) == 1 and names.count(RUNNER) == 1,
                'One exact restricted runner required')
        require(re.findall(r'android:targetPackage(?:\(0x[0-9a-fA-F]+\))?="([^"]+)"', tree) == [package],
                'Exact isolated target required')
    else:
        require('E: instrumentation' not in tree, 'No app instrumentation declaration')

def validate_log(text, phase):
    require(phase in PHASES, 'Unknown storage phase')
    require(not re.search(r'shortMsg=|AssumptionViolated|(?:^|\n).*Exception', text), 'No hidden exception/skip')
    if phase == 'contracts':
        return load_pinned('scripts/verification/android-regression/validate.py').validate_instrumentation(text, CONTRACT_TESTS)
    require(re.findall(r'(?m)^OK.*$', text) in [['OK (1 test)'], ['OK (1 test)\r']], 'Exact singleton summary')
    adapted = re.sub(r'(?m)^OK \(1 test\)(\r?)$', r'OK (1 tests)\1', text)
    return load_pinned('scripts/verification/android-regression/validate.py').validate_instrumentation(
        adapted, [CLASS + '#' + PHASES[phase][0]])

def validate_install_output(text):
    lines = text.splitlines()
    require(lines and lines[-1] == 'Success' and lines.count('Success') == 1
            and not re.search(r'Failure|Error|error|failed|INSTALL_FAILED', text), 'No exact successful install receipt')

def validate_report(report, run, invocation, phase):
    require(phase in PHASES and phase != 'contracts', 'Only a genuine scale phase has a report')
    require(report.get('schema') == 1 and report.get('buildRun') == run and report.get('invocation') == invocation,
            'Wrong report identity')
    require(report.get('phase') == phase and report.get('status') == 'PASS' and 'failure' not in report,
            'Phase did not genuinely finish')
    require(report.get('scope') == 'SYNTHETIC_OWNED_ROOM_PHASE_NOT_RELEASE_GATE', 'Wrong proof scope')
    require(report.get('api') == 36 and report.get('closed') is True, 'API/close proof missing')
    require(report.get('collectionHistoryOnly') is True, 'Exact approved collection-only retention mode required')
    require(report.get('nonPriceTablesUnchanged') is True, 'User/outbox tables changed')
    stats = report.get('database', {})
    for key in ('mainBytes', 'walBytes', 'pageSize', 'pageCount', 'freePages', 'usedBytes', 'dataAvailableBytes'):
        require(type(stats.get(key)) is int and stats[key] >= 0, 'Invalid byte/page receipt')
    require(stats['usedBytes'] == (stats['pageCount'] - stats['freePages']) * stats['pageSize'], 'Wrong live-page size')
    require(stats['pageSize'] in (4096, 8192, 16384, 32768, 65536), 'Unexpected SQLite page size')
    if phase == 'pilot':
        require(report.get('generatedRows') == 500000 and report.get('target13GiBReached') is False, 'Pilot is not 13GiB proof')
    else:
        require(report.get('seedUsedBytes', 0) >= 13 * GIB and report.get('target13GiBReached') is True,
                'Real 13GiB seed not proved')
        require(type(report.get('generatedRows')) is int and 367 * 50000 <= report['generatedRows'] <= 100000000
                and report['generatedRows'] % 50000 == 0, 'Unexpected real tuple count')
    if phase in ('seed', 'verify'):
        require(stats['mainBytes'] >= 13 * GIB and stats['usedBytes'] >= 13 * GIB, 'Current real live pages not 13GiB')
    if phase in ('verify', 'resume', 'reclaim'):
        require(report.get('integrity') == 'ok' and report.get('foreignKeyErrors') == 0, 'Integrity not proved')
    if phase in ('resume', 'reclaim'):
        require(report.get('retentionComplete') is True and report.get('retainedRowsMatch') is True
                and report.get('retainedHashMatch') is True, 'Retention oracle incomplete')
        require(report.get('retainedRows') == 52198, 'Exact latest50k plus six owned histories and two sentinels required')
    if phase == 'prune':
        require(report.get('batches') == 32 and report.get('deletedRowsThisPhase') == 98
                and report.get('retentionComplete') is False, 'Expected bounded partial prune with real committed deletions')
    if phase == 'resume':
        require(report.get('restartMode') == 'FRESH_PASS_RESCAN_NOT_SERIALIZED_CURSOR', 'False cursor-resume claim')
    if phase == 'reclaim':
        require(report.get('reclaimed') is True and stats['mainBytes'] < report.get('beforeReclaimMainBytes', 0)
                and stats['freePages'] == 0 and stats['walBytes'] == 0, 'No actual file reclamation')
    return {'phase': phase, 'status': 'PHASE_PASS_ONLY', 'notReleaseGate': True}
