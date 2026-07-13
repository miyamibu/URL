import XCTest
@testable import URLSaveriOS

final class SharedTagVisibilityTests: XCTestCase {
    func testPendingInviteShowsBanner() {
        XCTAssertTrue(
            shouldShowPendingInviteBanner(hasPendingInvite: true)
        )
    }

    func testMissingPendingInviteDoesNotShowBanner() {
        XCTAssertFalse(
            shouldShowPendingInviteBanner(hasPendingInvite: false),
            "共有タグ行は常時表示するが、招待がない空状態では保留中招待バナーを表示しない"
        )
    }
}
