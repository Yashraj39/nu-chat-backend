package com.pulsechat.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.concurrent.*;

@Service
public class RateLimiter {
 private final ConcurrentHashMap<String,Window> map=new ConcurrentHashMap<>();
 private record Window(long started,int count){}
 public boolean allow(String key,int limit){
   long now=System.currentTimeMillis()/60000;
   final boolean[] allowed={false};
   map.compute(key,(k,w)->{
     if(w==null||w.started()!=now){allowed[0]=true;return new Window(now,1);}
     if(w.count()>=limit){allowed[0]=false;return w;}
     allowed[0]=true;return new Window(now,w.count()+1);
   });
   return allowed[0];
 }
}
