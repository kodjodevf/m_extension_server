Pod::Spec.new do |s|
  s.name             = 'm_extension_server'
  s.version          = '0.0.4'
  s.summary          = 'Flutter extension server with an embedded OpenJDK Zero runtime on iOS.'
  s.description      = <<-DESC
Runs M-Extension-Server in-process on iOS using a lazily loaded, interpreter-only
OpenJDK framework. Android remains backed by the embedded Dalvik bridge and
desktop platforms launch a caller-supplied Java executable.
                       DESC
  s.homepage         = 'https://github.com/kodjodevf/m_extension_server'
  s.license          = { :file => '../LICENSE' }
  s.author           = { 'kodjodevf' => 'kodjodevf@users.noreply.github.com' }
  s.source           = { :path => '.' }

  s.source_files = 'Classes/**/*.{h,mm,swift}'
  s.public_header_files = 'Classes/MihonEmbeddedBridge.h'
  s.dependency 'Flutter'
  s.platform = :ios, '13.0'

  s.prepare_command = 'sh PrepareEmbeddedRuntime.sh'
  s.vendored_frameworks = 'Frameworks/OpenJDKRuntime.xcframework'
  s.resource_bundles = {
    'm_extension_server_runtime' => ['Runtime/**/*'],
    'm_extension_server_privacy' => ['Resources/PrivacyInfo.xcprivacy'],
  }
  s.preserve_paths = 'RuntimeSources/**/*', 'PrepareEmbeddedRuntime.sh'

  s.pod_target_xcconfig = {
    'DEFINES_MODULE' => 'YES',
    'CLANG_CXX_LANGUAGE_STANDARD' => 'gnu++20',
    'HEADER_SEARCH_PATHS[sdk=iphoneos*]' =>
      '$(inherited) "$(PODS_TARGET_SRCROOT)/Frameworks/OpenJDKRuntime.xcframework/ios-arm64/OpenJDKRuntime.framework/Headers"',
    'EXCLUDED_ARCHS[sdk=iphonesimulator*]' => 'i386',
  }
  s.swift_version = '5.0'
end
