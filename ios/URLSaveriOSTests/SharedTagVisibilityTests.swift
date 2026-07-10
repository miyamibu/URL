import XCTest
@testable import URLSaveriOS

final class SharedTagVisibilityTests: XCTestCase {
    func testConfiguredCloudShowsSharedTagEntryPoints() {
        XCTAssertTrue(
            shouldShowSharedTagCloudEntryPoints(
                isConfigured: true,
                hasSharedTags: false,
                hasPendingInvite: false
            )
        )
    }

    func testCachedSharedTagsStayVisibleWhenCloudConfigIsMissing() {
        XCTAssertTrue(
            shouldShowSharedTagCloudEntryPoints(
                isConfigured: false,
                hasSharedTags: true,
                hasPendingInvite: false
            )
        )
    }

    func testPendingInviteStaysVisibleWhenCloudConfigIsMissing() {
        XCTAssertTrue(
            shouldShowSharedTagCloudEntryPoints(
                isConfigured: false,
                hasSharedTags: false,
                hasPendingInvite: true
            )
        )
    }

    func testEmptyUnconfiguredStateStaysHidden() {
        XCTAssertFalse(
            shouldShowSharedTagCloudEntryPoints(
                isConfigured: false,
                hasSharedTags: false,
                hasPendingInvite: false
            )
        )
    }
}
