/*
 * All content copyright (c) 2003-2006 Terracotta, Inc., except as may otherwise be noted in a separate copyright notice.  All rights reserved.
 */
package com.tctest.spring.bean;

import org.springframework.aop.support.DefaultIntroductionAdvisor;

public class CounterSaverMixinAdvisor extends DefaultIntroductionAdvisor {

    public CounterSaverMixinAdvisor() {
        super(new CounterSaverMixin(), CounterSaver.class);
    }

}

