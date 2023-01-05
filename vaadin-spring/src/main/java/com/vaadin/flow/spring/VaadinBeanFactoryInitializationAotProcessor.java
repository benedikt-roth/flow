package com.vaadin.flow.spring;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.reflections.Reflections;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.aot.BeanFactoryInitializationAotContribution;
import org.springframework.beans.factory.aot.BeanFactoryInitializationAotProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.ComponentEvent;
import com.vaadin.flow.data.converter.Converter;
import com.vaadin.flow.router.HasErrorParameter;
import com.vaadin.flow.router.HasUrlParameter;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.router.RouteAlias;

class VaadinBeanFactoryInitializationAotProcessor
        implements BeanFactoryInitializationAotProcessor {

    @Override
    public BeanFactoryInitializationAotContribution processAheadOfTime(
            ConfigurableListableBeanFactory beanFactory) {
        return (generationContext, beanFactoryInitializationCode) -> {
            var hints = generationContext.getRuntimeHints();
            var reflection = hints.reflection();
            var resources = hints.resources();
            var memberCategories = MemberCategory.values();
            for (var pkg : getPackages(beanFactory)) {
                var reflections = new Reflections(pkg);

                /*
                 * This aims to register most types in the project that are
                 * needed for Flow to function properly. Examples are @Route
                 * annotated classes, Component and event classes which are
                 * instantiated through reflection etc
                 */

                Set<Class<?>> routeTypes = new HashSet<Class<?>>();
                routeTypes
                        .addAll(reflections.getTypesAnnotatedWith(Route.class));
                routeTypes.addAll(
                        reflections.getTypesAnnotatedWith(RouteAlias.class));
                for (var c : routeTypes) {
                    reflection.registerType(c, memberCategories);
                    resources.registerType(c);
                }
                for (var c : reflections.getSubTypesOf(Component.class))
                    reflection.registerType(c, memberCategories);
                for (var c : reflections.getSubTypesOf(HasErrorParameter.class))
                    reflection.registerType(c, memberCategories);
                for (var c : reflections.getSubTypesOf(ComponentEvent.class))
                    reflection.registerType(c, memberCategories);
                for (var c : reflections.getSubTypesOf(Converter.class))
                    reflection.registerType(c, memberCategories);
                for (var c : reflections.getSubTypesOf(HasUrlParameter.class))
                    reflection.registerType(c, memberCategories);
            }
        };
    }

    private static List<String> getPackages(BeanFactory beanFactory) {
        var listOf = new ArrayList<String>();
        listOf.add("com.vaadin");
        listOf.addAll(AutoConfigurationPackages.get(beanFactory));
        return listOf;
    }

}