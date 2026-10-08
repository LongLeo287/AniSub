package com.anisub.runtime.voice;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Bounded, single-flight catalog admission and durable LKG. Network transport belongs to DownloadManager. */
public final class CatalogRepository {
    public static final String URL="https://github.com/LongLeo287/AniSub/releases/download/catalog-v1/catalog.json";
    // DownloadManager hides redirect hops, so the reviewed catalog itself is pinned too.
    public static final String REMOTE_SHA256="ac12f19e02bb11434ef4f8eb5fb155da9ad26631d345790e35eea02a3d0b26c0";
    public static boolean trustedRemote(String json){
        try{
            byte[] hash=java.security.MessageDigest.getInstance("SHA-256").digest(json.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex=new StringBuilder();for(byte b:hash)hex.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
            return REMOTE_SHA256.equals(hex.toString());
        }catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    public interface Rename { boolean move(File from,File to); }
    private final File live,backup; private final VoiceCatalog bundled; private final Rename rename;
    private VoiceCatalog current; private long generation; private boolean busy;
    public CatalogRepository(File directory,VoiceCatalog bundled)throws IOException {this(directory,bundled,File::renameTo);}
    public CatalogRepository(File directory,VoiceCatalog bundled,Rename rename)throws IOException {
        if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException("catalog storage");
        this.live=new File(directory,"catalog.json");this.backup=new File(directory,"catalog.lkg.json");this.bundled=bundled;this.current=bundled;this.rename=rename;
        for(File file:new File[]{backup,live})if(file.isFile())try{
            String json=read(file);VoiceCatalog parsed=VoiceCatalog.parse(json);
            if(parsed.catalogVersion>current.catalogVersion)current=VoiceCatalog.update(json,bundled,current);
        }catch(Exception ignored){/* retain bundled/last validated file */}
    }
    public synchronized VoiceCatalog current(){return current;}
    public synchronized boolean busy(){return busy;}
    /** -1 means another request owns the only slot. */
    public synchronized long begin(){if(busy)return -1;busy=true;return ++generation;}
    public synchronized void cancel(){generation++;busy=false;}
    public synchronized void failed(long token){if(token==generation)busy=false;}
    public synchronized boolean accept(long token,String json)throws Exception{
        if(!busy||token!=generation)return false;
        File stage=new File(live.getParentFile(),"catalog.pending");
        boolean moved=false;
        try{
            VoiceCatalog next=VoiceCatalog.update(json,bundled,current);
            try(FileOutputStream out=new FileOutputStream(stage)){out.write(json.getBytes(StandardCharsets.UTF_8));out.getFD().sync();}
            if(live.exists()){
                if(backup.exists()&&!backup.delete())throw new IOException("catalog backup");
                if(!rename.move(live,backup))throw new IOException("catalog backup rename");moved=true;
            }
            if(!rename.move(stage,live)){if(moved)rename.move(backup,live);throw new IOException("catalog replace");}
            current=next;busy=false;return true;
        }finally{if(stage.exists())stage.delete();if(token==generation)busy=false;}
    }
    public static String read(File file)throws IOException{
        if(file.length()>VoiceCatalog.MAX_BYTES)throw new IOException("catalog size");
        try(FileInputStream in=new FileInputStream(file);java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream()){
            byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){if(out.size()+n>VoiceCatalog.MAX_BYTES)throw new IOException("catalog size");out.write(b,0,n);}
            String text=new String(out.toByteArray(),StandardCharsets.UTF_8);if(text.length()>VoiceCatalog.MAX_TEXT_UNITS)throw new IOException("catalog size");return text;
        }
    }
}
