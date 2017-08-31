package com.github.df.restypass.command;

import com.github.df.restypass.annotation.RestyMethod;
import com.github.df.restypass.annotation.RestyService;
import com.github.df.restypass.annotation.processor.RestyMethodProcessor;
import com.github.df.restypass.annotation.processor.RestyServiceProcessor;
import com.github.df.restypass.command.update.UpdateCommandConfig;
import com.github.df.restypass.command.update.Updater;
import com.github.df.restypass.http.client.HttpClientWrapper;
import com.github.df.restypass.http.config.AsyncHttpConfigFactory;
import com.github.df.restypass.spring.wrapper.SpringAnnotationWrapper;
import com.google.common.collect.HashBasedTable;
import org.apache.commons.lang3.StringUtils;
import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.AsyncHttpClientConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * RestyCommand所依赖的Context
 * 存储各种依赖数据
 * Created by darrenfu on 17-6-21.
 */
@SuppressWarnings("WeakerAccess")
public class RestyCommandContext implements Updater<UpdateCommandConfig> {

    private static final Logger log = LoggerFactory.getLogger(RestyCommandContext.class);

    /**
     * 服务与注解 map
     */
    private ConcurrentHashMap<Class, RestyService> serviceMetaDataMap;

    /**
     * method -> 注解 map
     */
    @SuppressWarnings("MismatchedQueryAndUpdateOfCollection")
    private ConcurrentHashMap<Method, RestyMethod> methodMetaDataMap;

    /**
     * method -> RestyCommandConfig map
     */
    private ConcurrentHashMap<Method, RestyCommandConfig> commandPropertiesMap;

    /**
     * serviceName -> HttpClient map
     */
    private ConcurrentHashMap<String, HttpClientWrapper> httpClientPool;

    /**
     * method -> requestTemplate
     */
    private ConcurrentHashMap<Method, RestyRequestTemplate> requestTemplateMap;

    /**
     * serviceName -> path -> method
     */
    private HashBasedTable<String, String, Method> serviceMethodTable;

    /**
     * 注解处理器
     */
    private RestyServiceProcessor serviceProcessor;

    /**
     * 注解处理器
     */
    private RestyMethodProcessor methodProcessor;

    private RestyCommandContext() {
        this.serviceMetaDataMap = new ConcurrentHashMap<>();
        this.methodMetaDataMap = new ConcurrentHashMap<>();
        this.commandPropertiesMap = new ConcurrentHashMap<>();
        this.serviceProcessor = new RestyServiceProcessor();
        this.methodProcessor = new RestyMethodProcessor();
        this.requestTemplateMap = new ConcurrentHashMap<>();
        this.httpClientPool = new ConcurrentHashMap<>();
        serviceMethodTable = HashBasedTable.create();
    }

    /**
     * instance
     */
    private static RestyCommandContext commandContext = new RestyCommandContext();

    /**
     * 获取实例
     *
     * @return the instance
     */
    public static RestyCommandContext getInstance() {
        return commandContext;
    }


    /**
     * Store resty service.
     *
     * @param clz          the clz
     * @param restyService the resty service
     */
    private void storeRestyService(Class clz, RestyService restyService) {
        serviceMetaDataMap.putIfAbsent(clz, restyService);
    }

    /**
     * Store resty serviceMethod.
     *
     * @param method      the serviceMethod
     * @param restyMethod the resty serviceMethod
     */
    private void storeRestyMethod(Method method, RestyMethod restyMethod) {
        methodMetaDataMap.putIfAbsent(method, restyMethod);
    }


    /**
     * 为service创建Resty配置
     *
     * @param serviceClz the service clz
     */
    public void initContextForService(Class serviceClz) {
        RestyService restyService = (RestyService) serviceClz.getDeclaredAnnotation(RestyService.class);
        if (restyService == null) {
            return;
        }
        String serviceName = restyService.serviceName();

        // class->@RestyService
        this.storeRestyService(serviceClz, restyService);
        AsyncHttpClientConfig httpClientConfig = AsyncHttpConfigFactory.createConfig(restyService.connectTimeout(), restyService.requestTimeout());
        HttpClientWrapper clientHolder = new HttpClientWrapper(httpClientConfig);
        httpClientPool.putIfAbsent(serviceName, clientHolder);


        SpringAnnotationWrapper wrapper = new SpringAnnotationWrapper();

        RestyCommandConfig commandProperties = new RestyCommandConfig.DefaultRestyCommandConfig();
        processRestyServiceAnnotation(restyService, commandProperties);


        for (Method method : serviceClz.getMethods()) {
            //存储 httpMethod 和 restyCommandConfig
            RestyMethod restyMethod = method.getDeclaredAnnotation(RestyMethod.class);
            if (restyMethod != null) {
                storeRestyMethod(method, restyMethod);
                processRestyMethodAnnotation(restyMethod, commandProperties);
            }
            commandPropertiesMap.putIfAbsent(method, commandProperties);

            // 存储 httpMethod 和 requestTemplate
            RestyRequestTemplate restyRequestTemplate = wrapper.processAnnotation(serviceClz, method);
            requestTemplateMap.putIfAbsent(method, restyRequestTemplate);

            serviceMethodTable.put(serviceName, restyRequestTemplate.getPath(), method);
        }
        log.info("RestyCommandContext初始化成功！");
    }

    /**
     * 处理Resty 注解,生成 RestyCommandConfig
     * 合并RestyService和RestyMethod中的配置
     *
     * @param restyService      service注解
     * @param commandProperties the command properties
     * @return RestyCommandConfig resty command config
     * @see RestyService
     * @see RestyMethod
     */
    protected RestyCommandConfig processRestyServiceAnnotation(RestyService restyService, RestyCommandConfig commandProperties) {

        // 处理resty 注解
        serviceProcessor.processor(restyService, commandProperties);
        return commandProperties;
    }

    /**
     * 处理Resty 注解,生成 RestyCommandConfig
     * 合并RestyService和RestyMethod中的配置
     *
     * @param restyMethod       the resty method
     * @param commandProperties the command properties
     * @return RestyCommandConfig resty command config
     * @see RestyService
     * @see RestyMethod
     */
    protected RestyCommandConfig processRestyMethodAnnotation(RestyMethod restyMethod, RestyCommandConfig commandProperties) {
        // 处理resty 注解
        methodProcessor.processor(restyMethod, commandProperties);
        return commandProperties;
    }

    /**
     * Gets command properties.
     *
     * @param method the httpMethod
     * @return the command properties
     */
    public RestyCommandConfig getCommandProperties(Method method) {
        return commandPropertiesMap.get(method);
    }

    /**
     * Gets command properties.
     *
     * @param serviceName the service name
     * @param path        the path
     * @return the command properties
     */
    @SuppressWarnings("unchecked")
    protected List<RestyCommandConfig> getCommandProperties(String serviceName, String path) {
        if (StringUtils.isEmpty(serviceName)) {
            return Collections.EMPTY_LIST;
        }
        List<RestyCommandConfig> configList = new ArrayList<>();
        // path为空，获取service下所有method对应的config
        if (StringUtils.isEmpty(path)) {
            Map<String, Method> row = serviceMethodTable.row(serviceName);
            for (Method method : row.values()) {
                RestyCommandConfig config = commandPropertiesMap.get(method);
                if (config != null) {
                    configList.add(commandPropertiesMap.get(method));
                }
            }
        } else {
            Method method = serviceMethodTable.get(serviceName, path);
            if (method != null && commandPropertiesMap.get(method) != null) {
                configList.add(commandPropertiesMap.get(method));
            }
        }

        return configList;
    }


    /**
     * Gets resty service.
     *
     * @param clz the clz
     * @return the resty service
     */
    @SuppressWarnings("unused")
    public RestyService getRestyService(Class clz) {
        return serviceMetaDataMap.get(clz);
    }


    /**
     * Gets http client.
     *
     * @param serviceName the service name
     * @return the http client
     */
    public AsyncHttpClient getHttpClient(String serviceName) {
        HttpClientWrapper clientHolder = httpClientPool.get(serviceName);
        if (clientHolder == null) {
            throw new RuntimeException("获取http client失败");
        }
        return clientHolder.getClient();
    }


    /**
     * Gets request template.
     *
     * @param restyMethod the resty httpMethod
     * @return the request template
     */
    public RestyRequestTemplate getRequestTemplate(Method restyMethod) {
        return requestTemplateMap.get(restyMethod);
    }

    @Override
    public boolean refresh(UpdateCommandConfig updateCommandConfig) {
        List<RestyCommandConfig> configList = this.getCommandProperties(updateCommandConfig.getServiceName(), updateCommandConfig.getPath());
        for (RestyCommandConfig config : configList) {
            config.refresh(updateCommandConfig);
        }

        return true;
    }
}
