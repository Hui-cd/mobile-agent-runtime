#import "MobileAgentBridge.h"
#import <UIKit/UIKit.h>

static NSString *MAJSONString(id value) {
    if (![NSJSONSerialization isValidJSONObject:value]) {
        return @"{\"error\":\"BRIDGE_JSON_INVALID\"}";
    }
    NSData *data = [NSJSONSerialization dataWithJSONObject:value options:NSJSONWritingSortedKeys error:nil];
    return [[NSString alloc] initWithData:data encoding:NSUTF8StringEncoding];
}

static UIWindow *MAKeyWindow(void) {
    for (UIScene *scene in UIApplication.sharedApplication.connectedScenes) {
        if (![scene isKindOfClass:UIWindowScene.class] || scene.activationState != UISceneActivationStateForegroundActive) continue;
        for (UIWindow *window in ((UIWindowScene *)scene).windows) {
            if (window.isKeyWindow) return window;
        }
    }
    return nil;
}

static NSString *MAString(id value) {
    if ([value isKindOfClass:NSString.class]) return value;
    if ([value respondsToSelector:@selector(string)]) return [value string];
    return nil;
}

static NSString *MAText(UIView *view) {
    if ([view isKindOfClass:UILabel.class]) return ((UILabel *)view).text;
    if ([view isKindOfClass:UIButton.class]) return [((UIButton *)view) titleForState:UIControlStateNormal];
    if ([view isKindOfClass:UITextField.class]) return ((UITextField *)view).text;
    if ([view isKindOfClass:UITextView.class]) return ((UITextView *)view).text;
    NSString *label = view.accessibilityLabel;
    return label.length ? label : nil;
}

static void MACollect(UIView *view, NSString *path, NSMutableArray *nodes, NSUInteger limit) {
    if (nodes.count >= limit || view.hidden || view.alpha < 0.01) return;
    CGRect frame = [view convertRect:view.bounds toView:nil];
    BOOL visible = !CGRectIsEmpty(frame) && CGRectIntersectsRect(UIScreen.mainScreen.bounds, frame);
    NSString *text = MAText(view);
    BOOL meaningful = visible && (text.length || view.accessibilityIdentifier.length || view.isAccessibilityElement ||
                                  [view isKindOfClass:UIControl.class] || [view isKindOfClass:UIScrollView.class]);
    if (meaningful) {
        NSMutableDictionary *node = [@{
            @"path": path,
            @"class": NSStringFromClass(view.class),
            @"frame": @{
                @"x": @(CGRectGetMinX(frame)), @"y": @(CGRectGetMinY(frame)),
                @"width": @(CGRectGetWidth(frame)), @"height": @(CGRectGetHeight(frame))
            },
            @"enabled": @(![view respondsToSelector:@selector(isEnabled)] || ((UIControl *)view).enabled),
            @"interactive": @(view.userInteractionEnabled),
        } mutableCopy];
        if (text.length) node[@"text"] = text;
        if (view.accessibilityIdentifier.length) node[@"identifier"] = view.accessibilityIdentifier;
        if (view.accessibilityValue) node[@"value"] = MAString(view.accessibilityValue) ?: @"";
        [nodes addObject:node];
    }
    [view.subviews enumerateObjectsUsingBlock:^(UIView *child, NSUInteger index, BOOL *stop) {
        MACollect(child, [path stringByAppendingFormat:@"/%lu", (unsigned long)index], nodes, limit);
        if (nodes.count >= limit) *stop = YES;
    }];
}

static UIView *MAViewAtPath(NSString *path) {
    UIWindow *window = MAKeyWindow();
    if (!window || ![path hasPrefix:@"0"]) return nil;
    UIView *current = window;
    NSArray<NSString *> *parts = [path componentsSeparatedByString:@"/"];
    for (NSUInteger index = 1; index < parts.count; index++) {
        NSInteger childIndex = parts[index].integerValue;
        if (childIndex < 0 || childIndex >= (NSInteger)current.subviews.count) return nil;
        current = current.subviews[(NSUInteger)childIndex];
    }
    return current;
}

NSString *MABridgeObserveJSON(void) {
    __block NSString *result;
    void (^work)(void) = ^{
        UIWindow *window = MAKeyWindow();
        if (!window) {
            result = @"{\"error\":\"NO_FOREGROUND_WINDOW\"}";
            return;
        }
        NSMutableArray *nodes = [NSMutableArray array];
        MACollect(window, @"0", nodes, 600);
        result = MAJSONString(@{
            @"platform": @"ios",
            @"backend": @"ios_livecontainer_research",
            @"guest_bundle_id": NSBundle.mainBundle.bundleIdentifier ?: @"unknown",
            @"nodes": nodes,
            @"truncated": @(nodes.count >= 600),
        });
    };
    if (NSThread.isMainThread) work(); else dispatch_sync(dispatch_get_main_queue(), work);
    return result;
}

NSString *MABridgeActJSON(NSString *requestJSON) {
    NSData *data = [requestJSON dataUsingEncoding:NSUTF8StringEncoding];
    NSDictionary *request = data ? [NSJSONSerialization JSONObjectWithData:data options:0 error:nil] : nil;
    if (![request isKindOfClass:NSDictionary.class]) return @"{\"error\":\"INVALID_REQUEST\"}";
    __block NSDictionary *response;
    void (^work)(void) = ^{
        NSString *action = request[@"action"];
        UIView *view = MAViewAtPath(request[@"path"]);
        if (!view) { response = @{@"error": @"NODE_NOT_FOUND"}; return; }
        if ([action isEqualToString:@"activate"]) {
            BOOL activated = [view isKindOfClass:UIControl.class]
                ? ({ [(UIControl *)view sendActionsForControlEvents:UIControlEventTouchUpInside]; YES; })
                : [view accessibilityActivate];
            response = activated ? @{@"ok": @YES} : @{@"error": @"ACTIVATE_REJECTED"};
        } else if ([action isEqualToString:@"input"]) {
            NSString *text = request[@"text"];
            if (![text isKindOfClass:NSString.class]) { response = @{@"error": @"MISSING_TEXT"}; return; }
            if ([view isKindOfClass:UITextField.class]) {
                UITextField *field = (UITextField *)view;
                [field becomeFirstResponder]; field.text = text;
                [field sendActionsForControlEvents:UIControlEventEditingChanged];
                response = @{@"ok": @YES};
            } else if ([view isKindOfClass:UITextView.class]) {
                UITextView *textView = (UITextView *)view;
                [textView becomeFirstResponder]; textView.text = text;
                [NSNotificationCenter.defaultCenter postNotificationName:UITextViewTextDidChangeNotification object:textView];
                response = @{@"ok": @YES};
            } else response = @{@"error": @"NODE_NOT_EDITABLE"};
        } else if ([action isEqualToString:@"scroll"]) {
            if (![view isKindOfClass:UIScrollView.class]) { response = @{@"error": @"NODE_NOT_SCROLLABLE"}; return; }
            UIScrollView *scroll = (UIScrollView *)view;
            NSString *direction = request[@"direction"] ?: @"down";
            CGFloat dy = [direction isEqualToString:@"up"] ? -scroll.bounds.size.height * 0.7 : scroll.bounds.size.height * 0.7;
            CGFloat maxY = MAX(-scroll.adjustedContentInset.top,
                               scroll.contentSize.height - scroll.bounds.size.height + scroll.adjustedContentInset.bottom);
            CGPoint target = CGPointMake(scroll.contentOffset.x, MIN(maxY, MAX(-scroll.adjustedContentInset.top, scroll.contentOffset.y + dy)));
            [scroll setContentOffset:target animated:NO];
            response = @{@"ok": @YES, @"offset_y": @(target.y)};
        } else response = @{@"error": @"UNSUPPORTED_ACTION"};
    };
    if (NSThread.isMainThread) work(); else dispatch_sync(dispatch_get_main_queue(), work);
    return MAJSONString(response ?: @{@"error": @"BRIDGE_FAILED"});
}

__attribute__((constructor)) static void MABridgeLoaded(void) {
    NSString *processName = NSProcessInfo.processInfo.processName;
    NSString *bundleID = NSBundle.mainBundle.bundleIdentifier ?: @"unknown";
    if ([bundleID isEqualToString:@"ai.mobileagent.livecontainer"]) {
        // iOS 26+ requires LiveContainer's JIT-less branch. This research host
        // uses binaries externally signed with the same Apple development
        // identity, so persist a non-secret mode marker without importing a
        // private key/P12 into the host.
        [NSUserDefaults.standardUserDefaults setObject:@"external-development-signature"
                                                forKey:@"LCCertificatePassword"];
        [NSUserDefaults.standardUserDefaults synchronize];
    }
    NSDictionary *evidence = @{
        @"backend": @"ios_livecontainer_research",
        @"bridge_version": @"0.1.0",
        @"guest_bundle_id": bundleID,
        @"process": processName ?: @"unknown",
        @"loaded_at": @([[NSDate date] timeIntervalSince1970]),
    };
    NSData *data = [NSJSONSerialization dataWithJSONObject:evidence options:NSJSONWritingSortedKeys error:nil];
    NSString *documents = [NSHomeDirectory() stringByAppendingPathComponent:@"Documents"];
    [NSFileManager.defaultManager createDirectoryAtPath:documents withIntermediateDirectories:YES attributes:nil error:nil];
    [data writeToFile:[documents stringByAppendingPathComponent:@"mobile-agent-bridge-loaded.json"] atomically:YES];
    NSLog(@"MobileAgentBridge loaded backend=ios_livecontainer_research process=%@", processName);
}
