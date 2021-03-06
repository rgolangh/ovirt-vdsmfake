/**
 Copyright (c) 2012 Red Hat, Inc.

 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at

           http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
*/
package org.ovirt.vdsmfake;

import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;

import org.apache.log4j.PropertyConfigurator;
import org.ovirt.vdsmfake.rpc.json.JsonRpcServer;
import org.ovirt.vdsmfake.task.TaskProcessor;

/**
 * Init/release data when application starts/ends
 *
 *
 *
 */
public class AppLifecycleListener implements ServletContextListener {

    @Override
    public void contextDestroyed(ServletContextEvent event) {
        System.out.println("Application destroyed.");

        final TaskProcessor taskProcessor = TaskProcessor.getInstance();
        taskProcessor.destroy();
        JsonRpcServer.shutdown();
    }

    @Override
    public void contextInitialized(ServletContextEvent event) {
        System.out.println("Application initialized.");

        final Map<String, String> paramMap = new HashMap<String, String>();

        @SuppressWarnings("unchecked")
        final Enumeration<String> paramNames = event.getServletContext().getInitParameterNames();
        while(paramNames.hasMoreElements()) {
            final String key = paramNames.nextElement();
            paramMap.put(key, System.getProperty(key,event.getServletContext().getInitParameter(key)));
        }
        // Make sure that the logDir system property is always set for log4j configuration
        System.setProperty("logDir", paramMap.get("logDir"));

        //Initialize log4j
        PropertyConfigurator.configure(getClass().getResource("/log4j.xml"));

        // fill application global parameters
        AppConfig.getInstance().init(paramMap);

        final TaskProcessor taskProcessor = TaskProcessor.getInstance();
        taskProcessor.init();

        // if json is configured - start the json listener
        if (paramMap.containsKey("jsonListenPort")) {
            int jsonPort = Integer.parseInt(paramMap.get("jsonListenPort"));
            boolean encrypted = Boolean.parseBoolean(paramMap.get("jsonSecured"));
            String hostName = paramMap.get("jsonHost");
            JsonRpcServer server = new JsonRpcServer(hostName, jsonPort, encrypted);
            server.start();
        }

    }

}
