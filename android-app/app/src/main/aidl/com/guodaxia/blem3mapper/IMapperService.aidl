package com.guodaxia.blem3mapper;

interface IMapperService {
    String startMapping(String capturePath, String configJson, IBinder clientToken, IBinder shizukuToken) = 0;
    void stopMapping() = 1;
    String getStatus() = 2;
    String getLogs() = 3;
    void destroy() = 16777114;
}
