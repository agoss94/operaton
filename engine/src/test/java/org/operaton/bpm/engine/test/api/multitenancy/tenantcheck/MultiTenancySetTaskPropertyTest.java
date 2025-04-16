/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH
 * under one or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information regarding copyright
 * ownership. Camunda licenses this file to you under the Apache License,
 * Version 2.0; you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.operaton.bpm.engine.test.api.multitenancy.tenantcheck;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.operaton.bpm.engine.IdentityService;
import org.operaton.bpm.engine.ProcessEngineException;
import org.operaton.bpm.engine.TaskService;
import org.operaton.bpm.engine.task.Task;
import org.operaton.bpm.engine.task.TaskQuery;
import org.operaton.bpm.engine.test.junit5.ParameterizedTestExtension.Parameterized;
import org.operaton.bpm.engine.test.junit5.ParameterizedTestExtension.Parameters;
import org.operaton.bpm.engine.test.junit5.ProcessEngineExtension;
import org.operaton.bpm.engine.test.junit5.ProcessEngineTestExtension;
import org.operaton.bpm.engine.test.util.ClockTestUtil;
import org.operaton.bpm.engine.test.util.MethodInvocation;
import org.operaton.bpm.engine.test.util.TriConsumer;
import org.operaton.bpm.model.bpmn.Bpmn;
import org.operaton.bpm.model.bpmn.BpmnModelInstance;

@Parameterized
public class MultiTenancySetTaskPropertyTest {

  protected static final String TENANT_ONE = "tenant1";
  protected static final String PROCESS_DEFINITION_KEY = "oneTaskProcess";

  protected static final BpmnModelInstance ONE_TASK_PROCESS = Bpmn.createExecutableProcess(PROCESS_DEFINITION_KEY)
      .startEvent()
      .userTask()
      .endEvent()
      .done();

  @RegisterExtension
  protected static ProcessEngineExtension engineRule = ProcessEngineExtension.builder().build();
  @RegisterExtension
  protected static ProcessEngineTestExtension testRule = new ProcessEngineTestExtension(engineRule);

  // populated by data in constructor
  protected final String operationName;
  protected final TriConsumer<TaskService, String, Object> operation;
  protected final Object value;
  protected final String taskQueryBuilderMethodName;

  // initialized during @Before
  protected TaskService taskService;
  protected IdentityService identityService;
  protected String taskId;
  protected Task task;

  public MultiTenancySetTaskPropertyTest(String operationName,
                                         TriConsumer<TaskService, String, Object> operation,
                                         Object value,
                                         String taskQueryBuilderMethodName) {
    this.operationName = operationName;
    this.operation = operation;
    this.value = value;
    this.taskQueryBuilderMethodName = taskQueryBuilderMethodName;
  }

  /**
   * Parameters:
   * <p>
   * simpleMethodName: Used for readability (printing first argument as test method instead of a autogenerated consumer name)
   * methodToCall: The method to call during test cases
   * setValue: The value to use to set property to
   * taskQueryBuilderMethodName: The corresponding taskQuery builder method name to use for assertion purposes
   */
  @Parameters
  public static Collection<Object[]> data() {
    TriConsumer<TaskService, String, Object> setPriority = (taskService, taskId, value) -> taskService.setPriority(taskId, (Integer) value);
    TriConsumer<TaskService, String, Object> setName = (taskService, taskId, value) -> taskService.setName(taskId, (String) value);
    TriConsumer<TaskService, String, Object> setDescription = (taskService, taskId, value) -> taskService.setDescription(taskId, (String) value);
    TriConsumer<TaskService, String, Object> setDueDate = (taskService, taskId, value) -> taskService.setDueDate(taskId, (Date) value);
    TriConsumer<TaskService, String, Object> setFollowUpDate = (taskService, taskId, value) -> taskService.setFollowUpDate(taskId, (Date) value);

    return Arrays.asList(new Object[][] {
        { "setPriority", setPriority, 1, "taskPriority"},
        { "setName", setName, "name", "taskName" },
        { "setDescription", setDescription, "description", "taskDescription" },
        { "setDueDate", setDueDate, ClockTestUtil.setClockToDateWithoutMilliseconds(), "dueDate" },
        { "setFollowUpDate", setFollowUpDate, ClockTestUtil.setClockToDateWithoutMilliseconds(), "followUpDate" }
    });
  }

  @BeforeEach
  void init() {
    testRule.deployForTenant(TENANT_ONE, ONE_TASK_PROCESS);

    engineRule.getRuntimeService().startProcessInstanceByKey(PROCESS_DEFINITION_KEY);

    task = engineRule.getTaskService().createTaskQuery().singleResult();
    taskId = task.getId();
  }

  @TestTemplate
  void shouldSetOperationForTaskWithAuthenticatedTenant() {
    // given
    identityService.setAuthentication("aUserId", null, Collections.singletonList(TENANT_ONE));

    // when
    operation.accept(taskService, taskId, value);

    // then
    assertCorrespondingTaskQueryHasCount(1L);
  }

  @TestTemplate
  void shouldSetOperationForTaskWithNoAuthenticatedTenant() {
    // given
    identityService.setAuthentication("aUserId", null);

    // when/then
    assertThatThrownBy(
        () -> operation.accept(taskService, taskId, value))
        .isInstanceOf(ProcessEngineException.class)
        .hasMessageContaining("Cannot assign the task '" + task.getId() + "' because it belongs to no authenticated tenant.");

  }

  @TestTemplate
  void shouldSetOperationForTaskWithDisabledTenantCheck() {
    // given
    identityService.setAuthentication("aUserId", null);
    engineRule.getProcessEngineConfiguration().setTenantCheckEnabled(false);

    // when
    operation.accept(taskService, taskId, value);

    // then
    assertCorrespondingTaskQueryHasCount(1L);
  }

  private void assertCorrespondingTaskQueryHasCount(long count) {
    TaskQuery query = taskService.createTaskQuery().taskId(taskId);
    query = withTaskCriteria(query, taskQueryBuilderMethodName, value);

    assertThat(query.count()).isEqualTo(count);
  }

  private TaskQuery withTaskCriteria(TaskQuery taskQuery, String methodName, Object propertyValue) {
    return (TaskQuery) MethodInvocation.of(taskQuery, methodName, new Object[] { propertyValue }).invoke();
  }
}