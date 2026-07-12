import Foundation
import StoreKit

enum StorePurchaseServiceResult: Equatable, Sendable {
    case verified(plan: PlanType, billingPeriod: BillingPeriod)
    case authRequired
    case notConfigured
    case cancelled
    case pending
    case failed(String)
}

final class StoreKitPurchaseService: @unchecked Sendable {
    private let config: SharedTagCloudConfig
    private let sessionStore: SharedTagAuthSessionStore
    private var transactionUpdatesTask: Task<Void, Never>?

    init(config: SharedTagCloudConfig, sessionStore: SharedTagAuthSessionStore) {
        self.config = config
        self.sessionStore = sessionStore
    }

    deinit {
        transactionUpdatesTask?.cancel()
    }

    func startTransactionUpdates() {
        guard transactionUpdatesTask == nil else { return }
        transactionUpdatesTask = Task { [weak self] in
            for await verificationResult in Transaction.updates {
                await self?.handleTransactionUpdate(verificationResult)
            }
        }
    }

    func purchase(planType: PlanType, billingPeriod: BillingPeriod) async -> StorePurchaseServiceResult {
        guard config.isConfigured else { return .notConfigured }
        guard let session = try? sessionStore.load() else { return .authRequired }
        guard let appAccountToken = UUID(uuidString: session.authUserID) else {
            return .failed("購入アカウントを安全に紐付けできませんでした")
        }
        let productID = SubscriptionProductIDs.expectedProductID(planType: planType, billingPeriod: billingPeriod)

        do {
            guard let product = try await Product.products(for: [productID]).first else {
                return .failed("ストア商品が見つかりませんでした")
            }
            var purchaseOptions = Set<Product.PurchaseOption>()
            purchaseOptions.insert(.appAccountToken(appAccountToken))
            let purchaseResult = try await product.purchase(options: purchaseOptions)
            switch purchaseResult {
            case .success(let verificationResult):
                let transaction = try checkVerified(verificationResult)
                let verification = try await verifyTransaction(
                    session: session,
                    productID: productID,
                    transaction: transaction,
                    purchaseToken: verificationResult.jwsRepresentation
                )
                if verification.status == "verified" {
                    await transaction.finish()
                    return .verified(plan: planType, billingPeriod: billingPeriod)
                }
                return .pending
            case .userCancelled:
                return .cancelled
            case .pending:
                return .pending
            @unknown default:
                return .failed("購入状態を確認できませんでした")
            }
        } catch StoreKitPurchaseError.unverified {
            return .failed("購入情報を確認できませんでした")
        } catch {
            return .failed(error.localizedDescription)
        }
    }

    func refreshCurrentEntitlements() async {
        for await verificationResult in Transaction.currentEntitlements {
            guard case .verified(let transaction) = verificationResult else { continue }
            guard SubscriptionProductIDs.isKnownProductID(transaction.productID) else { continue }
            guard let session = try? sessionStore.load() else { continue }
            _ = try? await verifyTransaction(
                session: session,
                productID: transaction.productID,
                transaction: transaction,
                purchaseToken: verificationResult.jwsRepresentation
            )
        }
    }

    private func handleTransactionUpdate(_ verificationResult: VerificationResult<Transaction>) async {
        guard case .verified(let transaction) = verificationResult else { return }
        guard SubscriptionProductIDs.isKnownProductID(transaction.productID) else { return }
        guard let session = try? sessionStore.load() else { return }
        do {
            let verification = try await verifyTransaction(
                session: session,
                productID: transaction.productID,
                transaction: transaction,
                purchaseToken: verificationResult.jwsRepresentation
            )
            if verification.status == "verified" {
                await transaction.finish()
            }
        } catch {
            // Keep the transaction unfinished so a later update or entitlement refresh can retry.
        }
    }

    private func verifyTransaction(
        session: SharedTagAuthSession,
        productID: String,
        transaction: Transaction,
        purchaseToken: String
    ) async throws -> StorePurchaseVerificationResponse {
        let request = StorePurchaseVerificationRequest(
            storePlatform: "app_store",
            storeProductId: productID,
            purchaseToken: purchaseToken,
            storeTransactionId: String(transaction.id),
            appAccountToken: transaction.appAccountToken?.uuidString
        )
        let data = try await executeFunction(session: session, body: request)
        return try JSONDecoder().decode(StorePurchaseVerificationResponse.self, from: data)
    }

    private func executeFunction<T: Encodable>(
        session: SharedTagAuthSession,
        body: T
    ) async throws -> Data {
        guard let url = URL(string: config.supabaseURL.trimmingTrailingSlashes() + "/functions/v1/verify-store-purchase") else {
            throw StoreKitPurchaseError.invalidConfiguration
        }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.timeoutInterval = 30
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue(config.anonKey, forHTTPHeaderField: "apikey")
        request.setValue("Bearer \(session.accessToken)", forHTTPHeaderField: "Authorization")
        request.httpBody = try JSONEncoder().encode(body)

        let (data, response) = try await URLSession.shared.data(for: request)
        guard let httpResponse = response as? HTTPURLResponse else {
            throw StoreKitPurchaseError.invalidResponse
        }
        guard (200...299).contains(httpResponse.statusCode) else {
            let message = String(data: data, encoding: .utf8) ?? "購入検証に失敗しました"
            throw StoreKitPurchaseError.remote(message)
        }
        return data
    }

    private func checkVerified<T>(_ result: VerificationResult<T>) throws -> T {
        switch result {
        case .verified(let value):
            return value
        case .unverified:
            throw StoreKitPurchaseError.unverified
        }
    }
}

private enum StoreKitPurchaseError: Error {
    case invalidConfiguration
    case invalidResponse
    case unverified
    case remote(String)
}

private struct StorePurchaseVerificationRequest: Encodable {
    let storePlatform: String
    let storeProductId: String
    let purchaseToken: String
    let storeTransactionId: String?
    let appAccountToken: String?
}

private struct StorePurchaseVerificationResponse: Decodable {
    let status: String
    let verificationId: String?
    let grantId: String?
    let plan: String?
    let billingPeriod: String?
}

private extension SubscriptionProductIDs {
    static func isKnownProductID(_ productID: String) -> Bool {
        [PlanType.standard, .pro]
            .flatMap { plan in BillingPeriod.allCases.map { expectedProductID(planType: plan, billingPeriod: $0) } }
            .contains(productID)
    }
}

private extension String {
    func trimmingTrailingSlashes() -> String {
        var value = self
        while value.hasSuffix("/") {
            value.removeLast()
        }
        return value
    }
}
