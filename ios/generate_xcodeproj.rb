require "fileutils"
require "xcodeproj"

ios_development_team = ENV.fetch("URLSAVER_IOS_DEVELOPMENT_TEAM", "")
ios_code_sign_identity = ENV.fetch("URLSAVER_IOS_CODE_SIGN_IDENTITY", "")
ios_app_profile_specifier = ENV.fetch("URLSAVER_IOS_APP_PROFILE_SPECIFIER", "")
ios_extension_profile_specifier = ENV.fetch("URLSAVER_IOS_EXTENSION_PROFILE_SPECIFIER", "")
ios_disable_app_group_entitlements = ENV.fetch("URLSAVER_IOS_DISABLE_APP_GROUP_ENTITLEMENTS", "false") == "true"

manual_signing_enabled =
  !ios_development_team.empty? &&
  !ios_code_sign_identity.empty? &&
  !ios_app_profile_specifier.empty? &&
  !ios_extension_profile_specifier.empty?

project_path = File.expand_path("URLSaveriOS.xcodeproj", __dir__)
FileUtils.rm_rf(project_path)

project = Xcodeproj::Project.new(project_path)
project.instance_variable_set(:@object_version, 77)
project.root_object.compatibility_version = Xcodeproj::Constants::COMPATIBILITY_VERSION_BY_OBJECT_VERSION[77]
project.root_object.attributes["LastSwiftUpdateCheck"] = "1700"
project.root_object.attributes["LastUpgradeCheck"] = "1700"

project.build_configurations.each do |config|
  config.build_settings["IPHONEOS_DEPLOYMENT_TARGET"] = "17.0"
  config.build_settings["TARGETED_DEVICE_FAMILY"] = "1"
  config.build_settings["SUPPORTS_MACCATALYST"] = "NO"
  config.build_settings["SUPPORTED_PLATFORMS"] = "iphoneos iphonesimulator"
  config.build_settings["SDKROOT"] = "auto"
end

shared_group = project.main_group.new_group("URLSaverShared", "URLSaverShared")
app_group = project.main_group.new_group("URLSaveriOS", "URLSaveriOS")
extension_group = project.main_group.new_group("URLSaverShareExtension", "URLSaverShareExtension")
tests_group = project.main_group.new_group("URLSaveriOSTests", "URLSaveriOSTests")
config_group = project.main_group.new_group("Config", "Config")
secrets_config = config_group.new_file("URLSaverSecrets.xcconfig")

app_target = project.new_target(:application, "URLSaveriOS", :ios, "17.0")
extension_target = project.new_target(:app_extension, "URLSaverShareExtension", :ios, "17.0")
test_target = project.new_target(:unit_test_bundle, "URLSaveriOSTests", :ios, "17.0")

project.root_object.attributes["TargetAttributes"] = {
  app_target.uuid => {
    "CreatedOnToolsVersion" => "26.4.1",
    "ProvisioningStyle" => manual_signing_enabled ? "Manual" : "Automatic",
  },
  extension_target.uuid => {
    "CreatedOnToolsVersion" => "26.4.1",
    "ProvisioningStyle" => manual_signing_enabled ? "Manual" : "Automatic",
  },
  test_target.uuid => {
    "CreatedOnToolsVersion" => "26.4.1",
    "TestTargetID" => app_target.uuid,
  },
}

[app_target, extension_target, test_target].each do |target|
  target.build_configurations.each do |config|
    config.build_settings["SWIFT_VERSION"] = "6.0"
    config.build_settings["IPHONEOS_DEPLOYMENT_TARGET"] = "17.0"
    config.build_settings["CLANG_ENABLE_MODULES"] = "YES"
    config.build_settings["GENERATE_INFOPLIST_FILE"] = "NO"
    config.build_settings["TARGETED_DEVICE_FAMILY"] = "1"
    config.build_settings["SUPPORTS_MACCATALYST"] = "NO"
    config.build_settings["SUPPORTED_PLATFORMS"] = "iphoneos iphonesimulator"
    config.build_settings["SDKROOT"] = "auto"
    config.build_settings["CODE_SIGN_STYLE"] = manual_signing_enabled ? "Manual" : "Automatic"
    config.build_settings["CODE_SIGNING_ALLOWED[sdk=iphonesimulator*]"] = "NO"
    config.build_settings["CODE_SIGNING_REQUIRED[sdk=iphonesimulator*]"] = "NO"
  end
end

app_target.build_configurations.each do |config|
  config.base_configuration_reference = secrets_config
  config.build_settings["PRODUCT_BUNDLE_IDENTIFIER"] = "com.mibu.codebridge.ios"
  config.build_settings["INFOPLIST_FILE"] = "URLSaveriOS/Info.plist"
  unless ios_disable_app_group_entitlements
    config.build_settings["CODE_SIGN_ENTITLEMENTS"] = "URLSaveriOS/URLSaveriOS.entitlements"
  end
  config.build_settings["LD_RUNPATH_SEARCH_PATHS"] = "$(inherited) @executable_path/Frameworks"
  config.build_settings["PRODUCT_NAME"] = "URLSaveriOS"
  config.build_settings["DEFINES_MODULE"] = "YES"
  if manual_signing_enabled
    config.build_settings["DEVELOPMENT_TEAM"] = ios_development_team
    config.build_settings["CODE_SIGN_IDENTITY"] = ios_code_sign_identity
    config.build_settings["PROVISIONING_PROFILE_SPECIFIER"] = ios_app_profile_specifier
  end
end

extension_target.build_configurations.each do |config|
  config.build_settings["PRODUCT_BUNDLE_IDENTIFIER"] = "com.mibu.codebridge.ios.share"
  config.build_settings["INFOPLIST_FILE"] = "URLSaverShareExtension/Info.plist"
  unless ios_disable_app_group_entitlements
    config.build_settings["CODE_SIGN_ENTITLEMENTS"] = "URLSaverShareExtension/URLSaverShareExtension.entitlements"
  end
  config.build_settings["APPLICATION_EXTENSION_API_ONLY"] = "YES"
  config.build_settings["LD_RUNPATH_SEARCH_PATHS"] = "$(inherited) @executable_path/Frameworks @executable_path/../../Frameworks"
  config.build_settings["PRODUCT_NAME"] = "URLSaverShareExtension"
  config.build_settings["SKIP_INSTALL"] = "YES"
  config.build_settings["DEFINES_MODULE"] = "YES"
  if manual_signing_enabled
    config.build_settings["DEVELOPMENT_TEAM"] = ios_development_team
    config.build_settings["CODE_SIGN_IDENTITY"] = ios_code_sign_identity
    config.build_settings["PROVISIONING_PROFILE_SPECIFIER"] = ios_extension_profile_specifier
  end
end

test_target.build_configurations.each do |config|
  config.build_settings["PRODUCT_BUNDLE_IDENTIFIER"] = "com.mibu.codebridge.ios.tests"
  config.build_settings["INFOPLIST_FILE"] = "URLSaveriOSTests/Info.plist"
  config.build_settings["PRODUCT_NAME"] = "URLSaveriOSTests"
  config.build_settings["TEST_TARGET_NAME"] = "URLSaveriOS"
  config.build_settings["TEST_HOST"] = "$(BUILT_PRODUCTS_DIR)/URLSaveriOS.app/$(BUNDLE_EXECUTABLE_FOLDER_PATH)/URLSaveriOS"
  config.build_settings["BUNDLE_LOADER"] = "$(TEST_HOST)"
  config.build_settings["DEFINES_MODULE"] = "YES"
end

sqlite = project.frameworks_group.new_file("usr/lib/libsqlite3.tbd", "SDKROOT")
background_tasks = project.frameworks_group.new_file("System/Library/Frameworks/BackgroundTasks.framework", "SDKROOT")
security = project.frameworks_group.new_file("System/Library/Frameworks/Security.framework", "SDKROOT")
extension_target.frameworks_build_phase.add_file_reference(sqlite)
extension_target.frameworks_build_phase.add_file_reference(security)
app_target.frameworks_build_phase.add_file_reference(sqlite)
app_target.frameworks_build_phase.add_file_reference(background_tasks)
app_target.frameworks_build_phase.add_file_reference(security)
test_target.frameworks_build_phase.add_file_reference(security)

Dir.glob(File.join(__dir__, "URLSaverShared/**/*.swift")).sort.each do |path|
  file = shared_group.new_file(path.sub("#{File.join(__dir__, 'URLSaverShared')}/", ""))
  app_target.add_file_references([file])
  extension_target.add_file_references([file])
  test_target.add_file_references([file])
end

Dir.glob(File.join(__dir__, "URLSaveriOS/**/*.swift")).sort.each do |path|
  file = app_group.new_file(path.sub("#{File.join(__dir__, 'URLSaveriOS')}/", ""))
  app_target.add_file_references([file])
  if path.end_with?(File.join("App", "SharedTagSyncExecutor.swift"))
    test_target.add_file_references([file])
  end
end

Dir.glob(File.join(__dir__, "URLSaverShareExtension/**/*.swift")).sort.each do |path|
  file = extension_group.new_file(path.sub("#{File.join(__dir__, 'URLSaverShareExtension')}/", ""))
  extension_target.add_file_references([file])
end

Dir.glob(File.join(__dir__, "URLSaveriOSTests/**/*.swift")).sort.each do |path|
  file = tests_group.new_file(path.sub("#{File.join(__dir__, 'URLSaveriOSTests')}/", ""))
  test_target.add_file_references([file])
end

app_privacy_manifest = app_group.new_file("PrivacyInfo.xcprivacy")
extension_privacy_manifest = extension_group.new_file("PrivacyInfo.xcprivacy")
app_target.resources_build_phase.add_file_reference(app_privacy_manifest)
extension_target.resources_build_phase.add_file_reference(extension_privacy_manifest)

info_files = [
  "URLSaveriOS/Info.plist",
  "URLSaveriOS/URLSaveriOS.entitlements",
  "URLSaverShareExtension/Info.plist",
  "URLSaverShareExtension/URLSaverShareExtension.entitlements",
  "URLSaveriOSTests/Info.plist",
]

info_files.each do |path|
  project.main_group.new_file(path)
end

test_target.add_dependency(app_target)
app_target.add_dependency(extension_target)

embed_phase = app_target.new_copy_files_build_phase("Embed App Extensions")
embed_phase.symbol_dst_subfolder_spec = :plug_ins
embed_phase.add_file_reference(extension_target.product_reference)

shared_scheme = Xcodeproj::XCScheme.new
shared_scheme.configure_with_targets(app_target, test_target)
shared_scheme.set_launch_target(app_target)
shared_scheme.save_as(project_path, "URLSaveriOS", true)

project.save

workspace_dir = File.join(project_path, "project.xcworkspace")
FileUtils.mkdir_p(workspace_dir)
File.write(
  File.join(workspace_dir, "contents.xcworkspacedata"),
  <<~XML
    <?xml version="1.0" encoding="UTF-8"?>
    <Workspace version="1.0">
      <FileRef location="group:../URLSaveriOS.xcodeproj"></FileRef>
    </Workspace>
  XML
)

top_level_workspace_dir = File.expand_path("URLSaveriOS.xcworkspace", __dir__)
FileUtils.rm_rf(top_level_workspace_dir)
FileUtils.mkdir_p(top_level_workspace_dir)
File.write(
  File.join(top_level_workspace_dir, "contents.xcworkspacedata"),
  <<~XML
    <?xml version="1.0" encoding="UTF-8"?>
    <Workspace version="1.0">
      <FileRef location="group:URLSaveriOS.xcodeproj"></FileRef>
    </Workspace>
  XML
)
