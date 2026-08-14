#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

typedef void (^MExtensionServerEmbeddedMihonStartCompletion)(
    int32_t port,
    NSError *_Nullable error);
typedef void (^MExtensionServerEmbeddedMihonCompletion)(NSError *_Nullable error);
typedef void (^MExtensionServerEmbeddedMihonStatusCompletion)(
    BOOL isRunning,
    NSError *_Nullable error);

FOUNDATION_EXPORT void MExtensionServerEmbeddedMihonStart(
    int32_t port,
    MExtensionServerEmbeddedMihonStartCompletion completion);
FOUNDATION_EXPORT void MExtensionServerEmbeddedMihonPause(
    MExtensionServerEmbeddedMihonCompletion completion);
FOUNDATION_EXPORT void MExtensionServerEmbeddedMihonStop(
    MExtensionServerEmbeddedMihonCompletion completion);
FOUNDATION_EXPORT void MExtensionServerEmbeddedMihonIsRunning(
    MExtensionServerEmbeddedMihonStatusCompletion completion);

NS_ASSUME_NONNULL_END
