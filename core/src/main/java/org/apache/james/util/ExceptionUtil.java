package org.apache.james.util;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.util.List;

public class ExceptionUtil {
    public static String getExceptionDetail(Exception e) {
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();

        PrintStream printStream = new PrintStream(byteArrayOutputStream );

        e.printStackTrace(printStream);
        try {
            return byteArrayOutputStream.toString("utf-8");
        } catch (UnsupportedEncodingException ex) {
            throw new RuntimeException(ex);
        }
    }


    public static String getCallStack(){
        StringBuilder stringBuilder = new StringBuilder();
        StackTraceElement[] stackTraceElementList = Thread.currentThread().getStackTrace();
        if(stackTraceElementList!=null){
            for(StackTraceElement stackTraceElement : stackTraceElementList){
                stringBuilder.append(stackTraceElement.toString());
                stringBuilder.append("\r\n");
            }
        }
        return stringBuilder.toString();
    }

}
