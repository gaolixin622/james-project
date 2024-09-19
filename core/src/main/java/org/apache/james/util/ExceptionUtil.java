package org.apache.james.util;

public class ExceptionUtil {
    public static String getExceptionDetail(Exception e) {
        StringBuffer stringBuffer = new StringBuffer(e.toString() + "\n");

        StackTraceElement[] messages = e.getStackTrace();
        if (messages != null) {
            int length = messages.length;
            for (int i = 0; i < length; i++) {
                stringBuffer.append("\t" + messages[i].toString() + "\n");
            }
        }
        stringBuffer.append(e.getMessage());
        return stringBuffer.toString();
    }

}
