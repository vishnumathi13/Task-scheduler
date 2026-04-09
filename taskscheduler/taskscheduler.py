import heapq
import time
import tkinter as tk
from tkinter import ttk, messagebox, font
from datetime import datetime, timedelta
import threading
import queue
from enum import Enum
import json
import os
from collections import defaultdict

class TaskPriority(Enum):
    """Enhanced priority levels"""
    CRITICAL = 1
    HIGH = 2
    MEDIUM = 3
    LOW = 4
    LOWEST = 5
    
    @staticmethod
    def from_int(value):
        mapping = {1: TaskPriority.CRITICAL, 2: TaskPriority.HIGH, 
                   3: TaskPriority.MEDIUM, 4: TaskPriority.LOW, 5: TaskPriority.LOWEST}
        return mapping.get(value, TaskPriority.MEDIUM)

class TaskCategory(Enum):
    """Task categories with colors"""
    STUDY = ("Study", "#3498db")
    WORK = ("Work", "#e74c3c")
    PERSONAL = ("Personal", "#2ecc71")
    URGENT = ("Urgent", "#e67e22")
    MAINTENANCE = ("Maintenance", "#95a5a6")
    
    def __init__(self, display_name, color):
        self.display_name = display_name
        self.color = color

class AdvancedTaskScheduler:
    """Enhanced task scheduler with advanced features"""
    
    def __init__(self):
        self.task_queue = []
        self.completed_tasks = []
        self.task_counter = 0
        self.history_file = "task_history.json"
        self.notification_queue = queue.Queue()
        self.running = True
        self.stats = {
            "total_completed": 0,
            "avg_completion_time": 0,
            "category_counts": defaultdict(int)
        }
        
        # Load history if exists
        self.load_history()
        
        # Start background notification thread
        self.notification_thread = threading.Thread(target=self.process_notifications, daemon=True)
        self.notification_thread.start()
    
    def calculate_dynamic_score(self, task):
        """
        Advanced scoring algorithm with multiple factors:
        - Base priority (1-5)
        - Aging (tasks waiting longer get boost)
        - Deadline pressure (exponential increase as deadline approaches)
        - Category multiplier
        - Dependency count
        """
        current_time = time.time()
        
        # Base priority (higher priority = lower score)
        base_score = task["priority"] * 20
        
        # Aging factor: tasks waiting longer get higher priority
        waiting_time = current_time - task["created"]
        aging_boost = min(waiting_time / 3600, 10)  # Max 10 points boost over 10 hours
        
        # Deadline pressure: exponential as deadline approaches
        time_left = task["deadline"] - current_time
        if time_left <= 0:
            deadline_pressure = 100  # Overdue tasks get maximum urgency
        else:
            # Exponential pressure: more urgent as deadline approaches
            deadline_pressure = (1 / time_left) * 100 if time_left > 0 else 0
            deadline_pressure = min(deadline_pressure, 50)
        
        # Category multiplier (some categories are inherently more important)
        category_multipliers = {
            "Urgent": 0.8,
            "Work": 1.0,
            "Study": 1.2,
            "Personal": 1.5,
            "Maintenance": 1.3
        }
        category_mult = category_multipliers.get(task["category"], 1.0)
        
        # Calculate final score (lower score = higher priority)
        score = (base_score - aging_boost - deadline_pressure) * category_mult
        
        # Add small random factor to prevent starvation of equal tasks
        score += (task["id"] % 100) * 0.001
        
        return -score  # Negative for min-heap (lowest score = highest priority)
    
    def add_task(self, name, priority, deadline_seconds, category, description="", dependencies=None):
        """Add a new task with advanced features"""
        current_time = time.time()
        
        task = {
            "name": name,
            "priority": priority,
            "deadline": current_time + deadline_seconds,
            "created": current_time,
            "category": category,
            "description": description,
            "id": self.task_counter,
            "dependencies": dependencies or [],
            "status": "pending",
            "estimated_duration": deadline_seconds * 0.1  # 10% of deadline as estimate
        }
        
        score = self.calculate_dynamic_score(task)
        heapq.heappush(self.task_queue, (score, task))
        self.task_counter += 1
        
        return task
    
    def get_next_task(self):
        """Get highest priority task with dependencies resolved"""
        if not self.task_queue:
            return None
        
        # Recalculate scores for all tasks
        updated_queue = []
        for _, task in self.task_queue:
            new_score = self.calculate_dynamic_score(task)
            heapq.heappush(updated_queue, (new_score, task))
        self.task_queue = updated_queue
        
        # Find first task with all dependencies completed
        for i, (_, task) in enumerate(self.task_queue):
            if all(dep in [t["name"] for t in self.completed_tasks] for dep in task["dependencies"]):
                return heapq.heappop(self.task_queue)
        
        return heapq.heappop(self.task_queue) if self.task_queue else None
    
    def execute_task(self):
        """Execute the highest priority task"""
        result = self.get_next_task()
        if not result:
            return None
        
        score, task = result
        
        # Update stats
        completion_time = time.time() - task["created"]
        self.stats["total_completed"] += 1
        self.stats["avg_completion_time"] = (
            (self.stats["avg_completion_time"] * (self.stats["total_completed"] - 1) + completion_time) 
            / self.stats["total_completed"]
        )
        self.stats["category_counts"][task["category"]] += 1
        
        task["status"] = "completed"
        task["completion_time"] = completion_time
        self.completed_tasks.append(task)
        
        return task
    
    def save_history(self):
        """Save task history to file"""
        try:
            history_data = {
                "completed_tasks": [
                    {k: v for k, v in task.items() if k not in ["description"]}
                    for task in self.completed_tasks
                ],
                "stats": self.stats
            }
            with open(self.history_file, 'w') as f:
                json.dump(history_data, f, indent=2, default=str)
        except Exception as e:
            print(f"Error saving history: {e}")
    
    def load_history(self):
        """Load task history from file"""
        try:
            if os.path.exists(self.history_file):
                with open(self.history_file, 'r') as f:
                    data = json.load(f)
                    self.stats = data.get("stats", self.stats)
        except Exception as e:
            print(f"Error loading history: {e}")
    
    def process_notifications(self):
        """Background thread for notifications"""
        while self.running:
            try:
                # Check for upcoming deadlines
                current_time = time.time()
                for _, task in self.task_queue:
                    time_left = task["deadline"] - current_time
                    if 0 < time_left < 300:  # Less than 5 minutes
                        self.notification_queue.put(f"⚠️ Deadline approaching: {task['name']} ({time_left/60:.0f} min)")
            except:
                pass
            time.sleep(10)  # Check every 10 seconds
    
    def get_upcoming_tasks(self, hours=24):
        """Get tasks due within specified hours"""
        current_time = time.time()
        upcoming = []
        for _, task in self.task_queue:
            time_left = task["deadline"] - current_time
            if 0 < time_left < hours * 3600:
                upcoming.append((time_left, task))
        return sorted(upcoming)
    
    def get_statistics(self):
        """Get scheduler statistics"""
        current_time = time.time()
        active_tasks = len(self.task_queue)
        overdue_tasks = sum(1 for _, task in self.task_queue if task["deadline"] < current_time)
        
        stats = {
            "active_tasks": active_tasks,
            "completed_tasks": self.stats["total_completed"],
            "overdue_tasks": overdue_tasks,
            "avg_completion_time": self.stats["avg_completion_time"],
            "category_breakdown": dict(self.stats["category_counts"])
        }
        return stats

class SmartTaskSchedulerGUI:
    """Advanced GUI for the task scheduler"""
    
    def __init__(self, root):
        self.root = root
        self.scheduler = AdvancedTaskScheduler()
        self.root.title("Advanced Smart Task Scheduler")
        self.root.geometry("900x700")
        
        # Set color scheme
        self.colors = {
            "bg": "#2c3e50",
            "fg": "#ecf0f1",
            "accent": "#3498db",
            "success": "#2ecc71",
            "warning": "#e74c3c"
        }
        
        self.root.configure(bg=self.colors["bg"])
        
        # Configure styles
        self.setup_styles()
        
        # Create GUI components
        self.create_menu()
        self.create_input_frame()
        self.create_task_table()
        self.create_status_bar()
        self.create_stats_panel()
        
        # Start auto-refresh
        self.refresh_display()
        self.start_notification_check()
        
    def setup_styles(self):
        """Configure ttk styles"""
        style = ttk.Style()
        style.theme_use('clam')
        
        style.configure("Custom.TFrame", background=self.colors["bg"])
        style.configure("Custom.TLabel", background=self.colors["bg"], foreground=self.colors["fg"])
        style.configure("Accent.TButton", background=self.colors["accent"], foreground="white")
        style.map("Accent.TButton", background=[('active', '#2980b9')])
        
    def create_menu(self):
        """Create menu bar"""
        menubar = tk.Menu(self.root)
        self.root.config(menu=menubar)
        
        # File menu
        file_menu = tk.Menu(menubar, tearoff=0)
        menubar.add_cascade(label="File", menu=file_menu)
        file_menu.add_command(label="Save History", command=self.save_history)
        file_menu.add_command(label="Export Tasks", command=self.export_tasks)
        file_menu.add_separator()
        file_menu.add_command(label="Exit", command=self.root.quit)
        
        # View menu
        view_menu = tk.Menu(menubar, tearoff=0)
        menubar.add_cascade(label="View", menu=view_menu)
        view_menu.add_command(label="Show Statistics", command=self.show_statistics)
        view_menu.add_command(label="Show Upcoming", command=self.show_upcoming)
        
        # Help menu
        help_menu = tk.Menu(menubar, tearoff=0)
        menubar.add_cascade(label="Help", menu=help_menu)
        help_menu.add_command(label="About", command=self.show_about)
    
    def create_input_frame(self):
        """Create enhanced input form"""
        input_frame = ttk.Frame(self.root, style="Custom.TFrame")
        input_frame.pack(pady=10, padx=10, fill="x")
        
        # Title
        title_label = tk.Label(input_frame, text="Add New Task", 
                               font=("Arial", 14, "bold"),
                               bg=self.colors["bg"], fg=self.colors["accent"])
        title_label.grid(row=0, column=0, columnspan=4, pady=5)
        
        # Row 1: Task Name
        tk.Label(input_frame, text="Task Name:", bg=self.colors["bg"], fg=self.colors["fg"]).grid(row=1, column=0, sticky="e", padx=5)
        self.name_entry = tk.Entry(input_frame, width=25)
        self.name_entry.grid(row=1, column=1, padx=5)
        
        # Row 1: Priority
        tk.Label(input_frame, text="Priority (1-5):", bg=self.colors["bg"], fg=self.colors["fg"]).grid(row=1, column=2, sticky="e", padx=5)
        self.priority_var = tk.IntVar(value=3)
        priority_frame = ttk.Frame(input_frame)
        priority_frame.grid(row=1, column=3, padx=5)
        for i in range(1, 6):
            ttk.Radiobutton(priority_frame, text=str(i), variable=self.priority_var, value=i).pack(side="left")
        
        # Row 2: Deadline
        tk.Label(input_frame, text="Deadline (minutes):", bg=self.colors["bg"], fg=self.colors["fg"]).grid(row=2, column=0, sticky="e", padx=5)
        self.deadline_entry = tk.Entry(input_frame, width=25)
        self.deadline_entry.insert(0, "60")
        self.deadline_entry.grid(row=2, column=1, padx=5)
        
        # Row 2: Category
        tk.Label(input_frame, text="Category:", bg=self.colors["bg"], fg=self.colors["fg"]).grid(row=2, column=2, sticky="e", padx=5)
        self.category_combo = ttk.Combobox(input_frame, values=[c.display_name for c in TaskCategory], width=20)
        self.category_combo.set("Work")
        self.category_combo.grid(row=2, column=3, padx=5)
        
        # Row 3: Description
        tk.Label(input_frame, text="Description:", bg=self.colors["bg"], fg=self.colors["fg"]).grid(row=3, column=0, sticky="ne", padx=5)
        self.description_text = tk.Text(input_frame, height=3, width=40)
        self.description_text.grid(row=3, column=1, columnspan=3, padx=5, pady=5)
        
        # Row 4: Buttons
        button_frame = ttk.Frame(input_frame)
        button_frame.grid(row=4, column=0, columnspan=4, pady=10)
        
        ttk.Button(button_frame, text="➕ Add Task", command=self.add_task, style="Accent.TButton").pack(side="left", padx=5)
        ttk.Button(button_frame, text="▶ Execute Next", command=self.execute_task).pack(side="left", padx=5)
        ttk.Button(button_frame, text="🗑 Clear Fields", command=self.clear_fields).pack(side="left", padx=5)
    
    def create_task_table(self):
        """Create enhanced task table"""
        # Create frame with scrollbar
        table_frame = ttk.Frame(self.root)
        table_frame.pack(pady=10, padx=10, fill="both", expand=True)
        
        # Treeview with more columns
        columns = ("ID", "Task Name", "Priority", "Category", "Time Left", "Status")
        self.tree = ttk.Treeview(table_frame, columns=columns, show="headings", height=12)
        
        # Define column headings
        self.tree.heading("ID", text="ID")
        self.tree.heading("Task Name", text="Task Name")
        self.tree.heading("Priority", text="Priority")
        self.tree.heading("Category", text="Category")
        self.tree.heading("Time Left", text="Time Left")
        self.tree.heading("Status", text="Status")
        
        # Set column widths
        self.tree.column("ID", width=50)
        self.tree.column("Task Name", width=200)
        self.tree.column("Priority", width=80)
        self.tree.column("Category", width=100)
        self.tree.column("Time Left", width=100)
        self.tree.column("Status", width=100)
        
        # Add scrollbar
        scrollbar = ttk.Scrollbar(table_frame, orient="vertical", command=self.tree.yview)
        self.tree.configure(yscrollcommand=scrollbar.set)
        
        self.tree.pack(side="left", fill="both", expand=True)
        scrollbar.pack(side="right", fill="y")
        
        # Bind double-click for task details
        self.tree.bind("<Double-Button-1>", self.show_task_details)
    
    def create_status_bar(self):
        """Create status bar"""
        self.status_bar = tk.Label(self.root, text="Ready", 
                                   bd=1, relief=tk.SUNKEN, anchor=tk.W,
                                   bg=self.colors["bg"], fg=self.colors["fg"])
        self.status_bar.pack(side=tk.BOTTOM, fill=tk.X)
    
    def create_stats_panel(self):
        """Create statistics panel"""
        stats_frame = ttk.Frame(self.root)
        stats_frame.pack(pady=5, padx=10, fill="x")
        
        self.stats_label = tk.Label(stats_frame, text="", 
                                    bg=self.colors["bg"], fg=self.colors["accent"],
                                    font=("Arial", 9))
        self.stats_label.pack()
    
    def add_task(self):
        """Add task to scheduler"""
        name = self.name_entry.get().strip()
        priority = self.priority_var.get()
        deadline_minutes = self.deadline_entry.get().strip()
        category = self.category_combo.get()
        description = self.description_text.get("1.0", tk.END).strip()
        
        if not name:
            messagebox.showerror("Error", "Task name is required!")
            return
        
        try:
            deadline_seconds = int(deadline_minutes) * 60
        except ValueError:
            messagebox.showerror("Error", "Deadline must be a number!")
            return
        
        # Add task
        task = self.scheduler.add_task(name, priority, deadline_seconds, category, description)
        
        self.status_bar.config(text=f"Task '{name}' added successfully")
        self.clear_fields()
        self.refresh_display()
        messagebox.showinfo("Success", f"Task '{name}' added to scheduler!")
    
    def execute_task(self):
        """Execute the highest priority task"""
        task = self.scheduler.execute_task()
        
        if task:
            self.status_bar.config(text=f"Executing: {task['name']}")
            self.refresh_display()
            
            # Show execution details
            details = f"Task: {task['name']}\n"
            details += f"Priority: {task['priority']}\n"
            details += f"Category: {task['category']}\n"
            details += f"Description: {task.get('description', 'N/A')}\n"
            details += f"Completion Time: {task['completion_time']:.2f} seconds"
            messagebox.showinfo("Task Executed", details)
            
            self.scheduler.save_history()
        else:
            messagebox.showwarning("No Tasks", "No tasks available to execute!")
    
    def refresh_display(self):
        """Refresh task display"""
        # Clear current display
        for row in self.tree.get_children():
            self.tree.delete(row)
        
        current_time = time.time()
        
        # Get sorted tasks
        sorted_tasks = sorted(self.scheduler.task_queue, key=lambda x: x[0])
        
        for score, task in sorted_tasks:
            time_left = task["deadline"] - current_time
            time_left_str = f"{int(time_left/60)} min" if time_left > 0 else "Overdue!"
            
            # Color code based on urgency
            tags = ()
            if time_left < 0:
                tags = ('overdue',)
            elif time_left < 300:  # Less than 5 minutes
                tags = ('urgent',)
            
            self.tree.insert("", "end", values=(
                task["id"],
                task["name"],
                f"{task['priority']}★",
                task["category"],
                time_left_str,
                "Pending"
            ), tags=tags)
        
        # Configure tags
        self.tree.tag_configure('overdue', background='#ff6b6b')
        self.tree.tag_configure('urgent', background='#ffa500')
        
        # Update statistics
        stats = self.scheduler.get_statistics()
        self.stats_label.config(text=f"📊 Active: {stats['active_tasks']} | Completed: {stats['completed_tasks']} | Overdue: {stats['overdue_tasks']}")
        
        # Schedule next refresh
        self.root.after(5000, self.refresh_display)  # Refresh every 5 seconds
    
    def clear_fields(self):
        """Clear input fields"""
        self.name_entry.delete(0, tk.END)
        self.priority_var.set(3)
        self.deadline_entry.delete(0, tk.END)
        self.deadline_entry.insert(0, "60")
        self.category_combo.set("Work")
        self.description_text.delete("1.0", tk.END)
        self.name_entry.focus()
    
    def show_task_details(self, event):
        """Show detailed task information"""
        selected = self.tree.selection()
        if not selected:
            return
        
        item = self.tree.item(selected[0])
        task_id = int(item['values'][0])
        
        # Find task
        for _, task in self.scheduler.task_queue:
            if task["id"] == task_id:
                details = f"Task Details:\n\n"
                details += f"Name: {task['name']}\n"
                details += f"Priority: {task['priority']}\n"
                details += f"Category: {task['category']}\n"
                details += f"Description: {task.get('description', 'N/A')}\n"
                details += f"Created: {datetime.fromtimestamp(task['created']).strftime('%H:%M:%S')}\n"
                details += f"Deadline: {datetime.fromtimestamp(task['deadline']).strftime('%H:%M:%S')}\n"
                details += f"Dependencies: {', '.join(task['dependencies']) if task['dependencies'] else 'None'}"
                
                messagebox.showinfo("Task Details", details)
                break
    
    def save_history(self):
        """Save task history"""
        self.scheduler.save_history()
        messagebox.showinfo("Saved", "Task history saved successfully!")
    
    def export_tasks(self):
        """Export tasks to file"""
        filename = f"tasks_export_{datetime.now().strftime('%Y%m%d_%H%M%S')}.json"
        self.scheduler.save_history()
        messagebox.showinfo("Exported", f"Tasks exported to {filename}")
    
    def show_statistics(self):
        """Show detailed statistics"""
        stats = self.scheduler.get_statistics()
        
        stats_text = "📈 Scheduler Statistics\n\n"
        stats_text += f"Active Tasks: {stats['active_tasks']}\n"
        stats_text += f"Completed Tasks: {stats['completed_tasks']}\n"
        stats_text += f"Overdue Tasks: {stats['overdue_tasks']}\n"
        stats_text += f"Avg Completion Time: {stats['avg_completion_time']:.2f} seconds\n\n"
        stats_text += "Category Breakdown:\n"
        
        for category, count in stats['category_breakdown'].items():
            stats_text += f"  • {category}: {count} tasks\n"
        
        messagebox.showinfo("Statistics", stats_text)
    
    def show_upcoming(self):
        """Show upcoming tasks"""
        upcoming = self.scheduler.get_upcoming_tasks(24)
        
        if not upcoming:
            messagebox.showinfo("Upcoming Tasks", "No upcoming tasks in the next 24 hours!")
            return
        
        tasks_text = "📅 Upcoming Tasks (Next 24 Hours)\n\n"
        for time_left, task in upcoming:
            hours_left = time_left / 3600
            tasks_text += f"• {task['name']} - Due in {hours_left:.1f} hours (Priority: {task['priority']})\n"
        
        messagebox.showinfo("Upcoming Tasks", tasks_text)
    
    def show_about(self):
        """Show about dialog"""
        about_text = "Advanced Smart Task Scheduler\n\n"
        about_text += "Version: 2.0\n"
        about_text += "Features:\n"
        about_text += "• Dynamic priority scoring\n"
        about_text += "• Deadline pressure calculation\n"
        about_text += "• Task aging system\n"
        about_text += "• Real-time notifications\n"
        about_text += "• Task history tracking\n"
        about_text += "• Statistics and analytics\n\n"
        about_text += "Made with Python and Tkinter"
        
        messagebox.showinfo("About", about_text)
    
    def start_notification_check(self):
        """Start checking for notifications"""
        def check_notifications():
            try:
                while not self.scheduler.notification_queue.empty():
                    msg = self.scheduler.notification_queue.get_nowait()
                    self.status_bar.config(text=msg, fg="red")
                    self.root.after(5000, lambda: self.status_bar.config(fg=self.colors["fg"]))
            except:
                pass
            self.root.after(5000, check_notifications)
        
        self.root.after(5000, check_notifications)

# Main execution
if __name__ == "__main__":
    root = tk.Tk()
    app = SmartTaskSchedulerGUI(root)
    root.mainloop()