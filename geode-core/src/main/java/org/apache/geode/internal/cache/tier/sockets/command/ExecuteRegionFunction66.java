/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package org.apache.geode.internal.cache.tier.sockets.command;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

import org.apache.geode.cache.Region;
import org.apache.geode.cache.client.internal.ConnectionImpl;
import org.apache.geode.cache.client.internal.ExecuteFunctionHelper;
import org.apache.geode.cache.execute.Function;
import org.apache.geode.cache.execute.FunctionException;
import org.apache.geode.cache.execute.FunctionInvocationTargetException;
import org.apache.geode.cache.execute.FunctionService;
import org.apache.geode.cache.operations.ExecuteFunctionOperationContext;
import org.apache.geode.cache.query.QueryInvocationTargetException;
import org.apache.geode.internal.Version;
import org.apache.geode.internal.cache.DistributedRegion;
import org.apache.geode.internal.cache.PartitionedRegion;
import org.apache.geode.internal.cache.execute.AbstractExecution;
import org.apache.geode.internal.cache.execute.DistributedRegionFunctionExecutor;
import org.apache.geode.internal.cache.execute.InternalFunctionInvocationTargetException;
import org.apache.geode.internal.cache.execute.MemberMappedArgument;
import org.apache.geode.internal.cache.execute.PartitionedRegionFunctionExecutor;
import org.apache.geode.internal.cache.execute.ServerToClientFunctionResultSender;
import org.apache.geode.internal.cache.execute.ServerToClientFunctionResultSender65;
import org.apache.geode.internal.cache.tier.CachedRegionHelper;
import org.apache.geode.internal.cache.tier.Command;
import org.apache.geode.internal.cache.tier.MessageType;
import org.apache.geode.internal.cache.tier.ServerSideHandshake;
import org.apache.geode.internal.cache.tier.sockets.BaseCommand;
import org.apache.geode.internal.cache.tier.sockets.ChunkedMessage;
import org.apache.geode.internal.cache.tier.sockets.Message;
import org.apache.geode.internal.cache.tier.sockets.Part;
import org.apache.geode.internal.cache.tier.sockets.ServerConnection;
import org.apache.geode.internal.i18n.LocalizedStrings;
import org.apache.geode.internal.logging.log4j.LocalizedMessage;
import org.apache.geode.internal.security.AuthorizeRequest;
import org.apache.geode.internal.security.SecurityService;

/**
 * @since GemFire 6.6
 */
public class ExecuteRegionFunction66 extends BaseCommand {

  private static final ExecuteRegionFunction66 singleton = new ExecuteRegionFunction66();

  public static Command getCommand() {
    return singleton;
  }

  private ExecuteRegionFunction66() {}

  @Override
  public void cmdExecute(final Message clientMessage, final ServerConnection serverConnection,
      final SecurityService securityService, long start) throws IOException {
    String regionName = null;
    Object function = null;
    Object args = null;
    MemberMappedArgument memberMappedArg = null;
    final boolean isBucketsAsFilter;
    final byte isReExecute;
    Set<Object> filter = null;
    byte hasResult = 0;
    int removedNodesSize = 0;
    Set<Object> removedNodesSet = null;
    int filterSize = 0, partNumber = 0;
    CachedRegionHelper crHelper = serverConnection.getCachedRegionHelper();
    byte functionState = 0;
    int functionTimeout = ConnectionImpl.DEFAULT_CLIENT_FUNCTION_TIMEOUT;
    try {
      byte[] bytes = clientMessage.getPart(0).getSerializedForm();
      functionState = bytes[0];
      if (bytes.length >= 5
          && serverConnection.getClientVersion().ordinal() >= Version.GFE_8009.ordinal()) {
        functionTimeout = Part.decodeInt(bytes, 1);
      }
      if (functionState != 1) {
        hasResult = (byte) ((functionState & 2) - 1);
      } else {
        hasResult = functionState;
      }
      if (hasResult == 1) {
        serverConnection.setAsTrue(REQUIRES_RESPONSE);
        serverConnection.setAsTrue(REQUIRES_CHUNKED_RESPONSE);
      }
      regionName = clientMessage.getPart(1).getString();
      function = clientMessage.getPart(2).getStringOrObject();
      args = clientMessage.getPart(3).getObject();
      Part part = clientMessage.getPart(4);
      if (part != null) {
        Object obj = part.getObject();
        if (obj instanceof MemberMappedArgument) {
          memberMappedArg = (MemberMappedArgument) obj;
        }
      }
      byte[] flags = clientMessage.getPart(5).getSerializedForm();
      if (serverConnection.getClientVersion().ordinal() > Version.GFE_81.ordinal()) {
        isBucketsAsFilter = (flags[0] & ExecuteFunctionHelper.BUCKETS_AS_FILTER_MASK) != 0;
        isReExecute = (flags[0] & ExecuteFunctionHelper.IS_REXECUTE_MASK) != 0 ? (byte) 1 : 0;
      } else {
        isReExecute = flags[0];
        isBucketsAsFilter = false;
      }
      filterSize = clientMessage.getPart(6).getInt();
      if (filterSize != 0) {
        filter = new HashSet<Object>();
        partNumber = 7;
        for (int i = 0; i < filterSize; i++) {
          filter.add(clientMessage.getPart(partNumber + i).getStringOrObject());
        }
      }

      partNumber = 7 + filterSize;
      removedNodesSize = clientMessage.getPart(partNumber).getInt();

      if (removedNodesSize != 0) {
        removedNodesSet = new HashSet<Object>();
        partNumber = partNumber + 1;

        for (int i = 0; i < removedNodesSize; i++) {
          removedNodesSet.add(clientMessage.getPart(partNumber + i).getStringOrObject());
        }
      }

    } catch (ClassNotFoundException exception) {
      logger.warn(LocalizedMessage.create(
          LocalizedStrings.ExecuteRegionFunction_EXCEPTION_ON_SERVER_WHILE_EXECUTIONG_FUNCTION_0,
          function), exception);
      if (hasResult == 1) {
        writeChunkedException(clientMessage, exception, serverConnection);
      } else {
        writeException(clientMessage, exception, false, serverConnection);
      }
      serverConnection.setAsTrue(RESPONDED);
      return;
    }
    if (function == null || regionName == null) {
      String message = null;
      if (function == null) {
        message =
            LocalizedStrings.ExecuteRegionFunction_THE_INPUT_0_FOR_THE_EXECUTE_FUNCTION_REQUEST_IS_NULL
                .toLocalizedString("function");
      }
      if (regionName == null) {
        message =
            LocalizedStrings.ExecuteRegionFunction_THE_INPUT_0_FOR_THE_EXECUTE_FUNCTION_REQUEST_IS_NULL
                .toLocalizedString("region");
      }
      logger.warn("{}: {}", serverConnection.getName(), message);
      sendError(hasResult, clientMessage, message, serverConnection);
      return;
    }

    Region region = crHelper.getRegion(regionName);
    if (region == null) {
      String message =
          LocalizedStrings.ExecuteRegionFunction_THE_REGION_NAMED_0_WAS_NOT_FOUND_DURING_EXECUTE_FUNCTION_REQUEST
              .toLocalizedString(regionName);
      logger.warn("{}: {}", serverConnection.getName(), message);
      sendError(hasResult, clientMessage, message, serverConnection);
      return;
    }
    ServerSideHandshake handshake = serverConnection.getHandshake();
    int earlierClientReadTimeout = handshake.getClientReadTimeout();
    handshake.setClientReadTimeout(functionTimeout);
    ServerToClientFunctionResultSender resultSender = null;
    Function<?> functionObject = null;
    try {
      if (function instanceof String) {
        functionObject = FunctionService.getFunction((String) function);
        if (functionObject == null) {
          String message =
              LocalizedStrings.ExecuteRegionFunction_THE_FUNCTION_0_HAS_NOT_BEEN_REGISTERED
                  .toLocalizedString(function);
          logger.warn("{}: {}", serverConnection.getName(), message);
          sendError(hasResult, clientMessage, message, serverConnection);
          return;
        } else {
          byte functionStateOnServerSide = AbstractExecution.getFunctionState(functionObject.isHA(),
              functionObject.hasResult(), functionObject.optimizeForWrite());
          if (logger.isDebugEnabled()) {
            logger.debug("Function State on server side: {} on client: {}",
                functionStateOnServerSide, functionState);
          }
          if (functionStateOnServerSide != functionState) {
            String message =
                LocalizedStrings.FunctionService_FUNCTION_ATTRIBUTE_MISMATCH_CLIENT_SERVER
                    .toLocalizedString(function);
            logger.warn("{}: {}", serverConnection.getName(), message);
            sendError(hasResult, clientMessage, message, serverConnection);
            return;
          }
        }
      } else {
        functionObject = (Function) function;
      }

      // check if the caller is authorized to do this operation on server
      functionObject.getRequiredPermissions(regionName).forEach(securityService::authorize);
      AuthorizeRequest authzRequest = serverConnection.getAuthzRequest();
      final String functionName = functionObject.getId();
      final String regionPath = region.getFullPath();
      ExecuteFunctionOperationContext executeContext = null;
      if (authzRequest != null) {
        executeContext = authzRequest.executeFunctionAuthorize(functionName, regionPath, filter,
            args, functionObject.optimizeForWrite());
      }

      // Construct execution
      AbstractExecution execution = (AbstractExecution) FunctionService.onRegion(region);
      ChunkedMessage m = serverConnection.getFunctionResponseMessage();
      m.setTransactionId(clientMessage.getTransactionId());
      resultSender =
          new ServerToClientFunctionResultSender65(m, MessageType.EXECUTE_REGION_FUNCTION_RESULT,
              serverConnection, functionObject, executeContext);

      if (execution instanceof PartitionedRegionFunctionExecutor) {
        if ((hasResult == 1) && filter != null && filter.size() == 1) {
          ServerConnection.executeFunctionOnLocalNodeOnly((byte) 1);
        }
        execution = new PartitionedRegionFunctionExecutor((PartitionedRegion) region, filter, args,
            memberMappedArg, resultSender, removedNodesSet, isBucketsAsFilter);
      } else {
        execution = new DistributedRegionFunctionExecutor((DistributedRegion) region, filter, args,
            memberMappedArg, resultSender);
      }
      if (isReExecute == 1) {
        execution = execution.setIsReExecute();
      }
      if (logger.isDebugEnabled()) {
        logger.debug(
            "Executing Function: {} on Server: {} with Execution: {} functionState={} reExecute={} hasResult={}",
            functionObject.getId(), serverConnection, execution, functionState, isReExecute,
            hasResult);
      }
      if (hasResult == 1) {
        if (function instanceof String) {
          switch (functionState) {
            case AbstractExecution.NO_HA_HASRESULT_NO_OPTIMIZEFORWRITE:
              execution.execute((String) function).getResult();
              break;
            case AbstractExecution.HA_HASRESULT_NO_OPTIMIZEFORWRITE:
              execution.execute((String) function).getResult();
              break;
            case AbstractExecution.HA_HASRESULT_OPTIMIZEFORWRITE:
              execution.execute((String) function).getResult();
              break;
            case AbstractExecution.NO_HA_HASRESULT_OPTIMIZEFORWRITE:
              execution.execute((String) function).getResult();
              break;
          }
        } else {
          execution.execute(functionObject).getResult();
        }
      } else {
        if (function instanceof String) {
          switch (functionState) {
            case AbstractExecution.NO_HA_NO_HASRESULT_NO_OPTIMIZEFORWRITE:
              execution.execute((String) function);
              break;
            case AbstractExecution.NO_HA_NO_HASRESULT_OPTIMIZEFORWRITE:
              execution.execute((String) function);
              break;
          }
        } else {
          execution.execute(functionObject);
        }
        writeReply(clientMessage, serverConnection);
      }
    } catch (IOException ioe) {
      logger.warn(LocalizedMessage.create(
          LocalizedStrings.ExecuteRegionFunction_EXCEPTION_ON_SERVER_WHILE_EXECUTIONG_FUNCTION_0,
          function), ioe);
      final String message = LocalizedStrings.ExecuteRegionFunction_SERVER_COULD_NOT_SEND_THE_REPLY
          .toLocalizedString();
      sendException(hasResult, clientMessage, message, serverConnection, ioe);
    } catch (FunctionException fe) {
      String message = fe.getMessage();
      Object cause = fe.getCause();
      if (cause instanceof FunctionInvocationTargetException
          || cause instanceof QueryInvocationTargetException) {
        if (cause instanceof InternalFunctionInvocationTargetException) {
          // Fix for #44709: User should not be aware of
          // InternalFunctionInvocationTargetException. No instance of
          // InternalFunctionInvocationTargetException is giving useful
          // information to user to take any corrective action hence logging
          // this at fine level logging
          // 1> When bucket is moved
          // 2> Incase of HA FucntionInvocationTargetException thrown. Since
          // it is HA, fucntion will be reexecuted on right node
          // 3> Multiple target nodes found for single hop operation
          // 4> in case of HA member departed
          if (logger.isDebugEnabled()) {
            logger.debug(LocalizedMessage.create(
                LocalizedStrings.ExecuteFunction_EXCEPTION_ON_SERVER_WHILE_EXECUTIONG_FUNCTION_0,
                new Object[] {function}), fe);
          }
        } else if (functionObject.isHA()) {
          logger.warn(LocalizedMessage.create(
              LocalizedStrings.ExecuteRegionFunction_EXCEPTION_ON_SERVER_WHILE_EXECUTIONG_FUNCTION_0,
              function + " :" + message));
        } else {
          logger.warn(LocalizedMessage.create(
              LocalizedStrings.ExecuteRegionFunction_EXCEPTION_ON_SERVER_WHILE_EXECUTIONG_FUNCTION_0,
              function), fe);
        }

        resultSender.setException(fe);
      } else {
        if (setLastResultReceived(resultSender)) {
          logger.warn(LocalizedMessage.create(
              LocalizedStrings.ExecuteRegionFunction_EXCEPTION_ON_SERVER_WHILE_EXECUTIONG_FUNCTION_0,
              function), fe);
          sendException(hasResult, clientMessage, message, serverConnection, fe);
        }
      }

    } catch (Exception e) {
      if (setLastResultReceived(resultSender)) {
        logger.warn(LocalizedMessage.create(
            LocalizedStrings.ExecuteRegionFunction_EXCEPTION_ON_SERVER_WHILE_EXECUTIONG_FUNCTION_0,
            function), e);
        String message = e.getMessage();
        sendException(hasResult, clientMessage, message, serverConnection, e);
      }
    } finally {
      handshake.setClientReadTimeout(earlierClientReadTimeout);
      ServerConnection.executeFunctionOnLocalNodeOnly((byte) 0);
    }
  }


  private void sendException(byte hasResult, Message msg, String message,
      ServerConnection serverConnection, Throwable e) throws IOException {
    synchronized (msg) {
      if (hasResult == 1) {
        writeFunctionResponseException(msg, MessageType.EXCEPTION, message, serverConnection, e);
      } else {
        writeException(msg, e, false, serverConnection);
      }
      serverConnection.setAsTrue(RESPONDED);
    }
  }

  private void sendError(byte hasResult, Message msg, String message,
      ServerConnection serverConnection) throws IOException {
    synchronized (msg) {
      if (hasResult == 1) {
        writeFunctionResponseError(msg, MessageType.EXECUTE_REGION_FUNCTION_ERROR, message,
            serverConnection);
      } else {
        writeErrorResponse(msg, MessageType.EXECUTE_REGION_FUNCTION_ERROR, message,
            serverConnection);
      }
      serverConnection.setAsTrue(RESPONDED);
    }
  }

  protected static void writeFunctionResponseException(Message origMsg, int messageType,
      String message, ServerConnection serverConnection, Throwable e) throws IOException {
    ChunkedMessage functionResponseMsg = serverConnection.getFunctionResponseMessage();
    ChunkedMessage chunkedResponseMsg = serverConnection.getChunkedResponseMessage();
    int numParts = 0;
    if (functionResponseMsg.headerHasBeenSent()) {
      if (e instanceof FunctionException
          && e.getCause() instanceof InternalFunctionInvocationTargetException) {
        functionResponseMsg.setNumberOfParts(3);
        functionResponseMsg.addObjPart(e);
        functionResponseMsg.addStringPart(BaseCommand.getExceptionTrace(e));
        InternalFunctionInvocationTargetException fe =
            (InternalFunctionInvocationTargetException) e.getCause();
        functionResponseMsg.addObjPart(fe.getFailedNodeSet());
        numParts = 3;
      } else {
        functionResponseMsg.setNumberOfParts(2);
        functionResponseMsg.addObjPart(e);
        functionResponseMsg.addStringPart(BaseCommand.getExceptionTrace(e));
        numParts = 2;
      }
      if (logger.isDebugEnabled()) {
        logger.debug("{}: Sending exception chunk while reply in progress: ",
            serverConnection.getName(), e);
      }
      functionResponseMsg.setServerConnection(serverConnection);
      functionResponseMsg.setLastChunkAndNumParts(true, numParts);
      // functionResponseMsg.setLastChunk(true);
      functionResponseMsg.sendChunk(serverConnection);
    } else {
      chunkedResponseMsg.setMessageType(messageType);
      chunkedResponseMsg.setTransactionId(origMsg.getTransactionId());
      chunkedResponseMsg.sendHeader();
      if (e instanceof FunctionException
          && e.getCause() instanceof InternalFunctionInvocationTargetException) {
        chunkedResponseMsg.setNumberOfParts(3);
        chunkedResponseMsg.addObjPart(e);
        chunkedResponseMsg.addStringPart(BaseCommand.getExceptionTrace(e));
        InternalFunctionInvocationTargetException fe =
            (InternalFunctionInvocationTargetException) e.getCause();
        chunkedResponseMsg.addObjPart(fe.getFailedNodeSet());
        numParts = 3;
      } else {
        chunkedResponseMsg.setNumberOfParts(2);
        chunkedResponseMsg.addObjPart(e);
        chunkedResponseMsg.addStringPart(BaseCommand.getExceptionTrace(e));
        numParts = 2;
      }
      if (logger.isDebugEnabled()) {
        logger.debug("{}: Sending exception chunk: ", serverConnection.getName(), e);
      }
      chunkedResponseMsg.setServerConnection(serverConnection);
      chunkedResponseMsg.setLastChunkAndNumParts(true, numParts);
      chunkedResponseMsg.sendChunk(serverConnection);
    }
  }
}
