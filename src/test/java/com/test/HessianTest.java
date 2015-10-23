package com.test;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

import com.caucho.hessian.io.Hessian2Input;
import com.caucho.hessian.io.Hessian2Output;
import com.caucho.hessian.io.SerializerFactory;
import com.jarvis.cache.to.CacheWrapper;

public class HessianTest {
    private static SerializerFactory _serializerFactory= SerializerFactory.createDefault();
    public static void main(String[] args) throws Exception {
        long start=System.currentTimeMillis();
        CacheWrapper<Simple> wrapper=new CacheWrapper<Simple>();
        wrapper.setCacheObject(Simple.getSimple());
        String fileName="hessian.bin";
        for(int i=0; i < 1000; i++) {
            write(wrapper, new FileOutputStream(fileName));
        }
        long end=System.currentTimeMillis();
        System.out.println("write:" + (end - start));

        start=System.currentTimeMillis();
        for(int i=0; i < 1000; i++) {
            InputStream inputStream=new FileInputStream(fileName);
            if(i == 0) {
                System.out.println("size:" + inputStream.available());
            }
            read(inputStream);
        }
        end=System.currentTimeMillis();
        System.out.println("read:" + (end - start));
    }
    
    private static void write(Object obj, OutputStream os) throws Exception {
        
        Hessian2Output output=new Hessian2Output(os);
        output.setSerializerFactory(_serializerFactory);
        output.writeObject(obj);
        output.flush();
        output.close();
        // System.out.println(bo.toByteArray().length);
        // System.out.println(new JdkSerializer().serialize(wrapper).length);
    }

    private static void read(InputStream inputStream) throws Exception {
        Hessian2Input input=new Hessian2Input(inputStream);
        input.setSerializerFactory(_serializerFactory);
        // Simple someObject = kryo.readObject(input, Simple.class);
        @SuppressWarnings("unchecked")
        CacheWrapper<Simple> someObject=(CacheWrapper<Simple>)input.readObject();
        input.close();
        // System.out.println(someObject.getCacheObject());
    }
}
