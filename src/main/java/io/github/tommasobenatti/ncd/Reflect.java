package io.github.tommasobenatti.ncd;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

final class Reflect {
    private Reflect() { }

    static Field field(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) { /* Search inherited fields. */ }
        }
        throw new NoSuchFieldException(type.getName() + "." + name);
    }

    static Object get(Object instance, String name) throws ReflectiveOperationException {
        return field(instance.getClass(), name).get(instance);
    }

    static Object call(Object instance, String name) throws ReflectiveOperationException {
        return instance.getClass().getMethod(name).invoke(instance);
    }

    static Method method(Class<?> type, String name, int parameters) throws NoSuchMethodException {
        for (Method method : type.getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == parameters) return method;
        }
        throw new NoSuchMethodException(type.getName() + "." + name);
    }
}
