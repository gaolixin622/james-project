/****************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one   *
 * or more contributor license agreements.  See the NOTICE file *
 * distributed with this work for additional information        *
 * regarding copyright ownership.  The ASF licenses this file   *
 * to you under the Apache License, Version 2.0 (the            *
 * "License"); you may not use this file except in compliance   *
 * with the License.  You may obtain a copy of the License at   *
 *                                                              *
 *   http://www.apache.org/licenses/LICENSE-2.0                 *
 *                                                              *
 * Unless required by applicable law or agreed to in writing,   *
 * software distributed under the License is distributed on an  *
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY       *
 * KIND, either express or implied.  See the License for the    *
 * specific language governing permissions and limitations      *
 * under the License.                                           *
 ****************************************************************/

package org.apache.james.user.lib;

import java.sql.Timestamp;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.apache.commons.configuration2.HierarchicalConfiguration;
import org.apache.commons.configuration2.ex.ConfigurationException;
import org.apache.commons.configuration2.tree.ImmutableNode;
import org.apache.commons.lang3.StringUtils;
import org.apache.james.DefaultUserEntityValidator;
import org.apache.james.UserEntityValidator;
import org.apache.james.core.AuthLogger;
import org.apache.james.core.Domain;
import org.apache.james.core.MailAddress;
import org.apache.james.core.Username;
import org.apache.james.domainlist.api.DomainList;
import org.apache.james.domainlist.api.DomainListException;
import org.apache.james.lifecycle.api.Configurable;
import org.apache.james.user.api.AlreadyExistInUsersRepositoryException;
import org.apache.james.user.api.InvalidUsernameException;
import org.apache.james.user.api.UsersRepository;
import org.apache.james.user.api.UsersRepositoryException;
import org.apache.james.user.api.model.User;
import org.apache.james.util.DurationParser;
import org.apache.james.util.MDCBuilder;
import org.reactivestreams.Publisher;
import org.slf4j.LoggerFactory;

import com.google.common.base.CharMatcher;
import org.slf4j.MDC;

public class UsersRepositoryImpl<T extends UsersDAO> implements UsersRepository, Configurable {
    public static final org.slf4j.Logger LOGGER = LoggerFactory.getLogger(UsersRepositoryImpl.class);
    private static String ILLEGAL_USERNAME_CHARACTERS = "\"(),:; <>@[\\]";

    private static Cache<String, Set<String>> lockUserCache = Caffeine.newBuilder()
            .expireAfterWrite(1, TimeUnit.HOURS)
            .build();

    private static Cache<String, Set<String>> lockIpCache = Caffeine.newBuilder()
            .expireAfterWrite(1, TimeUnit.HOURS)
            .build();

    private final DomainList domainList;
    protected final T usersDAO;
    private boolean virtualHosting;
    private Optional<Username> administratorId;
    private long verifyFailureDelay;
    private UserEntityValidator validator;

    @Inject
    public UsersRepositoryImpl(DomainList domainList, T usersDAO) {
        this.domainList = domainList;
        this.usersDAO = usersDAO;
        this.validator = new DefaultUserEntityValidator(this);
    }

    @Inject
    public void setValidator(UserEntityValidator validator) {
        this.validator = validator;
    }

    @Override
    public void configure(HierarchicalConfiguration<ImmutableNode> configuration) throws ConfigurationException {
        virtualHosting = configuration.getBoolean("enableVirtualHosting", usersDAO.getDefaultVirtualHostingValue());
        administratorId = Optional.ofNullable(configuration.getString("administratorId"))
            .map(Username::of);
        verifyFailureDelay = Optional.ofNullable(configuration.getString("verifyFailureDelay"))
            .map(string -> DurationParser.parse(string, ChronoUnit.SECONDS).toMillis())
            .orElse(0L);
    }

    public void setEnableVirtualHosting(boolean virtualHosting) {
        this.virtualHosting = virtualHosting;
    }

    @Override
    public void assertValid(Username username) throws UsersRepositoryException {
        assertDomainPartValid(username);
        assertLocalPartValid(username);
    }

    protected void assertDomainPartValid(Username username) throws UsersRepositoryException {
        if (supportVirtualHosting()) {
            // need a @ in the username
            if (!username.hasDomainPart()) {
                throw new InvalidUsernameException("Given Username needs to contain a @domainpart");
            } else {
                Domain domain = username.getDomainPart().get();
                try {
                    if (!domainList.containsDomain(domain)) {
                        throw new InvalidUsernameException("Domain does not exist in DomainList");
                    }
                } catch (DomainListException e) {
                    throw new UsersRepositoryException("Unable to query DomainList", e);
                }
            }
        } else {
            // @ only allowed when virtualhosting is supported
            if (username.hasDomainPart()) {
                throw new InvalidUsernameException("Given Username contains a @domainpart but virtualhosting support is disabled");
            }
        }
    }

    private void assertLocalPartValid(Username username) throws InvalidUsernameException {
        boolean isValid = CharMatcher.anyOf(ILLEGAL_USERNAME_CHARACTERS)
            .matchesNoneOf(username.getLocalPart());
        if (!isValid) {
            throw new InvalidUsernameException(String.format("Given Username '%s' should not contain any of those characters: %s",
                username.asString(), ILLEGAL_USERNAME_CHARACTERS));
        }
    }

    @Override
    public void addUser(Username username, String password) throws UsersRepositoryException {
        ensureNoConflict(username);
        assertValid(username);
        usersDAO.addUser(username, password);
    }

    private void ensureNoConflict(Username username) throws UsersRepositoryException {
        try {
            Optional<UserEntityValidator.ValidationFailure> validationFailure = validator.canCreate(username);
            if (validationFailure.isPresent()) {
                throw new AlreadyExistInUsersRepositoryException(validationFailure.get().errorMessage());
            }
        } catch (UsersRepositoryException e) {
            throw e;
        } catch (Exception e) {
            throw new UsersRepositoryException("Unexpected exception", e);
        }
    }

    @Override
    public User getUserByName(Username name) throws UsersRepositoryException {
        return usersDAO.getUserByName(name).orElse(null);
    }

    @Override
    public boolean test(Username name, String password) throws UsersRepositoryException {
        boolean isVerified = usersDAO.getUserByName(name)
            .map(x ->  x.getIsLocked()!=null && x.getIsLocked()==0 && x.verifyPassword(password))
            .orElseGet(() -> {
                LOGGER.info("Could not retrieve user {}. Password is unverified.", name.asString());
                AuthLogger.LOGGER.error("Could not retrieve user {}. Password is unverified.", name.asString());
                return false;
            });




        if(!isVerified){
            String remoteIp = MDC.get(MDCBuilder.IP);

            if(StringUtils.isNotBlank(remoteIp)){
                String key = remoteIp + "_" + name.asString();

                Set<String> pwSet =  lockIpCache.getIfPresent(key);
                if(pwSet==null){
                    pwSet = new HashSet<>();
                    lockIpCache.put(key, pwSet);
                }

                pwSet.add(password);

                AuthLogger.LOGGER.error("{} times attempt, {}, {}, {}", pwSet.size(), remoteIp, name.asString(), password);

            }
        }


        if(!isVerified){
            String key = name.asString();
            Set<String> errorCount = lockUserCache.getIfPresent(key);
            if(errorCount==null){
                errorCount = new HashSet<>();
                lockUserCache.put(key, errorCount);
            }

            errorCount.add(password);
            if(errorCount.size()>10){
                Optional<? extends User>  findUser =  usersDAO.getUserByName(name);
                if(findUser.isPresent()){
                    User x = findUser.get();
                    x.setIsLocked(1);
                    x.setLockDt(new Timestamp(System.currentTimeMillis()));
                    try {
                        usersDAO.updateUser(x);
                        lockUserCache.invalidate(key);
                    } catch (UsersRepositoryException e) {
                        throw new RuntimeException(e);
                    }
                }
            }
        }


        if (!isVerified && verifyFailureDelay > 0L) {
            try {
                Thread.sleep(verifyFailureDelay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        if(!isVerified){
            AuthLogger.LOGGER.error("Auth failed, {}, {}", name.asString(), password);
        }else {
            AuthLogger.LOGGER.info("Auth success, {}", name.asString());
        }

        return isVerified;
    }

    @Override
    public void updateUser(User user) throws UsersRepositoryException {
        assertDomainPartValid(user.getUserName());
        usersDAO.updateUser(user);
    }

    @Override
    public void removeUser(Username name) throws UsersRepositoryException {
        assertDomainPartValid(name);
        usersDAO.removeUser(name);
    }

    @Override
    public boolean contains(Username name) throws UsersRepositoryException {
        return usersDAO.contains(name);
    }

    @Override
    public Publisher<Boolean> containsReactive(Username name) {
        return usersDAO.containsReactive(name);
    }

    @Override
    public int countUsers() throws UsersRepositoryException {
        return usersDAO.countUsers();
    }

    @Override
    public Iterator<Username> list() throws UsersRepositoryException {
        return usersDAO.list();
    }

    @Override
    public Publisher<Username> listReactive() {
        return usersDAO.listReactive();
    }

    @Override
    public boolean supportVirtualHosting() {
        return virtualHosting;
    }

    @Override
    public boolean isAdministrator(Username username) throws UsersRepositoryException {
        assertValid(username);

        return administratorId.map(id -> id.equals(username))
            .orElse(false);
    }

    @Override
    public boolean isReadOnly() {
        return false;
    }

    @Override
    public MailAddress getMailAddressFor(Username username) throws UsersRepositoryException {
        try {
            if (supportVirtualHosting()) {
                return new MailAddress(username.asString());
            }
            return new MailAddress(username.getLocalPart(), domainList.getDefaultDomain());
        } catch (Exception e) {
            throw new UsersRepositoryException("Failed to compute mail address associated with the user", e);
        }
    }
}
