package ai.mobileagent.virtualdisplay;
import android.content.Intent;
import android.os.ParcelFileDescriptor;
interface IExecutionBroker {
    int createDisplay() = 0;
    void launch(in Intent intent) = 1;
    void key(int keyCode) = 2;
    void releaseDisplay() = 3;
    String install(in ParcelFileDescriptor apk, long size) = 4;
    void destroy() = 16777114;
}
