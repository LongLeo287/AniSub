package com.anisub.runtime.ai;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
/** Fail in Java before sherpa's native token parser can terminate the process. */
public final class VoiceTokens {
    private VoiceTokens(){}
    public static void verify(File file) throws IOException {
        if(file.length()>256*1024)throw new IOException("TOKEN_FORMAT");
        try(InputStream in=new FileInputStream(file);ByteArrayOutputStream bytes=new ByteArrayOutputStream()){
            byte[] chunk=new byte[4096];int n;
            while((n=in.read(chunk))!=-1){if(bytes.size()+n>256*1024)throw new IOException("TOKEN_FORMAT");bytes.write(chunk,0,n);}
            validate(new String(bytes.toByteArray(),StandardCharsets.UTF_8));
        }
    }
    public static void validate(String text) throws IOException {
        if(text==null||text.length()>256*1024||text.indexOf('\r')>=0||text.indexOf('\uFFFD')>=0)throw new IOException("TOKEN_FORMAT");
        Set<String> symbols=new HashSet<>();Set<Integer> ids=new HashSet<>();
        String[] lines=text.split("\n",-1);
        for(int index=0;index<lines.length;index++){
            String line=lines[index];
            if(line.isEmpty()){if(index==lines.length-1)continue;throw new IOException("TOKEN_FORMAT");}
            int split=line.lastIndexOf(' ');
            if(split<1||split==line.length()-1)throw new IOException("TOKEN_FORMAT");
            String symbol=line.substring(0,split),raw=line.substring(split+1);
            if(symbol.codePointCount(0,symbol.length())!=1||!raw.matches("[0-9]{1,5}")
                    ||(!" ".equals(symbol)&&Character.isWhitespace(symbol.codePointAt(0))))throw new IOException("TOKEN_FORMAT");
            int id=Integer.parseInt(raw);
            if(id>10000||!symbols.add(symbol)||!ids.add(id))throw new IOException("TOKEN_FORMAT");
        }
        if(!symbols.contains("_")||!symbols.contains("^")||!symbols.contains("$")||!symbols.contains(" "))throw new IOException("TOKEN_FORMAT");
    }
}
