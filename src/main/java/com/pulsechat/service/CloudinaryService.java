package com.pulsechat.service;

import com.cloudinary.Cloudinary;
import com.cloudinary.utils.ObjectUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.util.*;

@Service
public class CloudinaryService {
 private final Cloudinary cloud; private final long max;
 private static final Set<String> ALLOWED=Set.of(
   "image/jpeg","image/png","image/webp","image/gif","application/pdf",
   "text/plain","text/csv","application/msword",
   "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
   "application/vnd.ms-excel","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
 );
 private static final Set<String> BAD=Set.of("exe","bat","cmd","sh","jar","msi","com","scr","ps1","vbs","dll");
 public CloudinaryService(@Value("${cloudinary.cloud-name}") String n,@Value("${cloudinary.api-key}") String k,
   @Value("${cloudinary.api-secret}") String s,@Value("${app.max-file-size}") long max){
   this.max=max;
   if(n.isBlank()||k.isBlank()||s.isBlank()) cloud=null; else cloud=new Cloudinary(ObjectUtils.asMap("cloud_name",n,"api_key",k,"api_secret",s));
 }
 public UploadResult upload(MultipartFile f) throws IOException {
   if(cloud==null) throw new IllegalStateException("Cloudinary is not configured.");
   if(f==null||f.isEmpty()) throw new IllegalArgumentException("Empty file.");
   if(f.getSize()>max) throw new IllegalArgumentException("File is too large. Maximum size is 10 MB.");
   String mime=f.getContentType()==null?"":f.getContentType().toLowerCase();
   String name=f.getOriginalFilename()==null?"file":f.getOriginalFilename().replaceAll("[^a-zA-Z0-9._ -]","_");
   String ext=name.contains(".")?name.substring(name.lastIndexOf('.')+1).toLowerCase():"";
   if(BAD.contains(ext)||!ALLOWED.contains(mime)) throw new IllegalArgumentException("This file type is not allowed.");
   Map<?,?> r=(Map<?,?>)cloud.uploader().upload(f.getBytes(),ObjectUtils.asMap(
      "resource_type","auto","folder","pulsechat","use_filename",true,"unique_filename",true));
   return new UploadResult((String)r.get("secure_url"),(String)r.get("public_id"),name,mime,f.getSize());
 }
 public record UploadResult(String url,String publicId,String originalName,String mimeType,long size){}
}
