package com.example.btfixoffline;

import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;

public class ReplaceTrue extends XC_MethodReplacement {
    @Override
    protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
        XposedBridge.log("BTFIX: isInternationalBuild() -> true");
        return Boolean.TRUE;
    }
}