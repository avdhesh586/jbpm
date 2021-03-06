package org.jbpm.examples.checklist.impl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jbpm.examples.checklist.ChecklistContext;
import org.jbpm.examples.checklist.ChecklistContextConstraint;
import org.jbpm.examples.checklist.ChecklistItem;
import org.jbpm.examples.checklist.ChecklistManager;
import org.jbpm.runtime.manager.impl.SimpleRuntimeEnvironment;
import org.jbpm.services.task.impl.model.GroupImpl;
import org.jbpm.services.task.impl.model.I18NTextImpl;
import org.jbpm.services.task.impl.model.PeopleAssignmentsImpl;
import org.jbpm.services.task.impl.model.TaskDataImpl;
import org.jbpm.services.task.impl.model.TaskImpl;
import org.jbpm.services.task.impl.model.UserImpl;
import org.kie.api.definition.process.WorkflowProcess;
import org.kie.api.runtime.KieSession;
import org.kie.api.runtime.manager.RuntimeEngine;
import org.kie.api.runtime.manager.RuntimeManager;
import org.kie.api.runtime.process.ProcessInstance;
import org.kie.api.task.TaskService;
import org.kie.api.task.model.I18NText;
import org.kie.api.task.model.OrganizationalEntity;
import org.kie.api.task.model.Task;
import org.kie.internal.runtime.manager.RuntimeEnvironment;
import org.kie.internal.runtime.manager.RuntimeManagerFactory;
import org.kie.internal.runtime.manager.context.EmptyContext;
import org.kie.internal.task.api.InternalTaskService;
import org.kie.internal.task.api.model.InternalPeopleAssignments;
import org.kie.internal.task.api.model.InternalTask;
import org.kie.internal.task.api.model.InternalTaskData;

public class DefaultChecklistManager implements ChecklistManager {

	private RuntimeManager manager;
	private List<ChecklistContext> contexts = new ArrayList<ChecklistContext>();
	private RuntimeEnvironment environment;
	
	public DefaultChecklistManager(RuntimeEnvironment environment) {
		this.environment = environment;
	}
	
	public List<ChecklistContext> getContexts() {
		return contexts;
	}

	public long createContext(String name) {
		RuntimeEngine runtime = getRuntime();
		ProcessInstance processInstance = runtime.getKieSession().startProcess(
			name == null ? "org.jbpm.examples.checklist.AdHocProcess" : name, null);
		manager.disposeRuntimeEngine(runtime);
		ChecklistContext context = new DefaultChecklistContext();
		contexts.add(context);
		return processInstance.getId();
	}

	public List<ChecklistItem> getTasks(long processInstanceId, List<ChecklistContextConstraint> contexts) {
		List<ChecklistItem> items = getTasks(processInstanceId);
		List<ChecklistItem> results = new ArrayList<ChecklistItem>();
		for (ChecklistItem item: items) {
			if (contexts != null) {
				for (ChecklistContextConstraint context: contexts) {
					if (!context.acceptsTask(item)) {
						break;
					}
				}
			}
			results.add(item);
		}
		return results;
	}
	
	public List<ChecklistItem> getTasks(long processInstanceId) {
		RuntimeEngine runtime = getRuntime();
		KieSession ksession = runtime.getKieSession();
		ProcessInstance processInstance = ksession.getProcessInstance(processInstanceId);
		Map<String, ChecklistItem> orderingIds = new HashMap<String, ChecklistItem>();
		if (processInstance != null) {
			List<ChecklistItem> result = ChecklistItemFactory.getPendingChecklistItems((WorkflowProcess)
				ksession.getKieBase().getProcess(processInstance.getProcessId()));
			for (ChecklistItem item: result) {
				if (item.getOrderingNb() != null && item.getOrderingNb().trim().length() > 0) { 
					orderingIds.put(item.getOrderingNb(), item);
				}
			}
		}
		TaskService taskService = runtime.getTaskService();
		List<Long> taskIds = taskService.getTasksByProcessInstanceId(processInstanceId);
		List<ChecklistItem> result = new ArrayList<ChecklistItem>();
		for (Long taskId: taskIds) {
			Task task = taskService.getTaskById(taskId);
			if (task != null) {
				ChecklistItem item = ChecklistItemFactory.createChecklistItem(task);
				if (item.getOrderingNb() != null) {
					orderingIds.put(item.getOrderingNb(), item);
				} else {
					result.add(item);
				}
			}
		}
		for (ChecklistItem item: orderingIds.values()) {
			result.add(item);
		}
		Collections.sort(result, new Comparator<ChecklistItem>() {
			public int compare(ChecklistItem o1, ChecklistItem o2) {
				if (o1.getOrderingNb() != null && o2.getOrderingNb() != null) {
					return o1.getOrderingNb().compareTo(o2.getOrderingNb());
				} else if (o1.getTaskId() != null && o2.getTaskId() != null) {
					return o1.getTaskId().compareTo(o2.getTaskId());
				} else {
					throw new IllegalArgumentException();
				}
			}
		});
		manager.disposeRuntimeEngine(runtime);
		return result;
	}

	public ChecklistItem addTask(String userId, String[] actorIds, String[] groupIds, String name, String orderingId, long processInstanceId) {
		RuntimeEngine runtime = getRuntime();
		
		InternalTask task = new TaskImpl();
        setTaskName(task, name);
        setTaskDescription(task, orderingId);
        //task.setPriority(priority);
        InternalTaskData taskData = new TaskDataImpl();
        taskData.setProcessInstanceId(processInstanceId);
        // taskData.setProcessSessionId(sessionId);
        taskData.setSkipable(false);
        taskData.setCreatedBy(new UserImpl(userId));
        task.setTaskData(taskData);
        
        InternalPeopleAssignments peopleAssignments = (InternalPeopleAssignments) task.getPeopleAssignments();
        if (peopleAssignments == null) {
        	peopleAssignments = new PeopleAssignmentsImpl();
        	peopleAssignments.setPotentialOwners(new ArrayList<OrganizationalEntity>());
        	peopleAssignments.setBusinessAdministrators(new ArrayList<OrganizationalEntity>());
        	peopleAssignments.setExcludedOwners(new ArrayList<OrganizationalEntity>());
        	peopleAssignments.setRecipients(new ArrayList<OrganizationalEntity>());
        	peopleAssignments.setTaskStakeholders(new ArrayList<OrganizationalEntity>());
        	task.setPeopleAssignments(peopleAssignments);
        }

        List<OrganizationalEntity> potentialOwners = new ArrayList<OrganizationalEntity>();
        for (String actorId: actorIds) {
        	potentialOwners.add(new UserImpl(actorId));
        }
        for (String groupId: groupIds) {
        	potentialOwners.add(new GroupImpl(groupId));
        }
        setTaskPotentialOwners(task, potentialOwners);
        List<OrganizationalEntity> businessAdministrators = peopleAssignments.getBusinessAdministrators();
        businessAdministrators.add(new UserImpl("Administrator"));
        
        TaskService taskService = runtime.getTaskService();
		long taskId = taskService.addTask(task, (Map<String, Object>) null);
		manager.disposeRuntimeEngine(runtime);
		return ChecklistItemFactory.createChecklistItem(taskService.getTaskById(taskId));
	}

	public void updateTaskName(long taskId, String name) {
		RuntimeEngine runtime = getRuntime();
		List<I18NText> names = new ArrayList<I18NText>();
		names.add(new I18NTextImpl("en-UK", name));
		((InternalTaskService) runtime.getTaskService()).setTaskNames(taskId, names);
		manager.disposeRuntimeEngine(runtime);
	}
	
	private void setTaskName(InternalTask task, String name) {
		List<I18NText> names = new ArrayList<I18NText>();
        names.add(new I18NTextImpl("en-UK", name));
        task.setNames(names);
		List<I18NText> subjects = new ArrayList<I18NText>();
		subjects.add(new I18NTextImpl("en-UK", name));
        task.setSubjects(subjects);
	}

	public void updateTaskDescription(long taskId, String description) {
		RuntimeEngine runtime = getRuntime();
		List<I18NText> descriptions = new ArrayList<I18NText>();
		descriptions.add(new I18NTextImpl("en-UK", description));
		((InternalTaskService) runtime.getTaskService()).setDescriptions(taskId, descriptions);
		manager.disposeRuntimeEngine(runtime);
	}
	
	private void setTaskDescription(InternalTask task, String description) {
		List<I18NText> descriptions = new ArrayList<I18NText>();
        descriptions.add(new I18NTextImpl("en-UK", description));
        task.setDescriptions(descriptions);
	}

	public void updateTaskPriority(long taskId, int priority) {
		RuntimeEngine runtime = getRuntime();
		((InternalTaskService) runtime.getTaskService()).setPriority(taskId, priority);
		manager.disposeRuntimeEngine(runtime);
	}

	public void updateTaskPotentialOwners(long taskId, List<OrganizationalEntity> potentialOwners) {
//		RuntimeEngine runtime = getRuntime();
//		runtime.getTaskService().set(taskId, potentialOwners);
//		manager.disposeRuntimeEngine(runtime);
	}
	
	private void setTaskPotentialOwners(Task task, List<OrganizationalEntity> potentialOwners) {
        ((InternalPeopleAssignments) task.getPeopleAssignments()).setPotentialOwners(potentialOwners);
	}

	public void claimTask(String userId, long taskId) {
		RuntimeEngine runtime = getRuntime();
		runtime.getTaskService().claim(taskId, userId);
		manager.disposeRuntimeEngine(runtime);
	}

	public void releaseTask(String userId, long taskId) {
		RuntimeEngine runtime = getRuntime();
		runtime.getTaskService().release(taskId, userId);
		manager.disposeRuntimeEngine(runtime);
	}

	public void completeTask(String userId, long taskId) {
		RuntimeEngine runtime = getRuntime();
		runtime.getTaskService().start(taskId, userId);
		runtime.getTaskService().complete(taskId, userId, null);
		manager.disposeRuntimeEngine(runtime);
	}
	
	public void abortTask(String userId, long taskId) {
		RuntimeEngine runtime = getRuntime();
		runtime.getTaskService().start(taskId, userId);
		runtime.getTaskService().fail(taskId, userId, null);
		manager.disposeRuntimeEngine(runtime);
	}

	protected RuntimeEngine getRuntime() {
		if (manager == null) {
			if (environment == null) {
				environment = new SimpleRuntimeEnvironment();
			}
			manager = RuntimeManagerFactory.Factory.get().newSingletonRuntimeManager(environment);        
		}
        return manager.getRuntimeEngine(EmptyContext.get());
	}
	
}
